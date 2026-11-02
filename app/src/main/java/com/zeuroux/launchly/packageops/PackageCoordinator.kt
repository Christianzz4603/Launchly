package com.zeuroux.launchly.packageops

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.zeuroux.launchly.data.AppPreferences
import com.zeuroux.launchly.data.ManagedVersionRepository
import com.zeuroux.launchly.model.Architecture
import com.zeuroux.launchly.model.InstalledPackage
import com.zeuroux.launchly.model.MINECRAFT_PACKAGE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.solrudev.ackpine.DisposableSubscriptionContainer
import ru.solrudev.ackpine.installer.InstallFailure
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.parameters.InstallParameters
import ru.solrudev.ackpine.installer.parameters.InstallerType
import ru.solrudev.ackpine.session.ProgressSession
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.uninstaller.PackageUninstaller
import ru.solrudev.ackpine.uninstaller.UninstallFailure
import ru.solrudev.ackpine.uninstaller.parameters.UninstallParameters
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

sealed interface PackageOperationState {
    data object Idle : PackageOperationState
    data class PermissionRequired(val versionId: String) : PackageOperationState
    data class DowngradeConfirmationRequired(
        val versionId: String,
        val currentVersion: String,
        val targetVersion: String
    ) : PackageOperationState
    data class Preparing(val versionId: String) : PackageOperationState
    data class Installing(val versionId: String, val progress: Int?) : PackageOperationState
    data class Uninstalling(val targetVersionId: String?) : PackageOperationState
    data class Failure(val message: String) : PackageOperationState
    data class Completed(val message: String) : PackageOperationState
}

sealed interface LaunchResult {
    data object Launched : LaunchResult
    data class Failure(val message: String) : LaunchResult
}

interface PackageCoordinator {
    val installedPackage: StateFlow<InstalledPackage?>
    val operation: StateFlow<PackageOperationState>
    fun refreshInstalledPackage()
    suspend fun importApk(uri: Uri)
    suspend fun install(versionId: String)
    suspend fun confirmDowngrade(versionId: String)
    suspend fun dismissOperation()
    suspend fun uninstallMinecraft()
    fun launchMinecraft(): LaunchResult
}

class AckpinePackageCoordinator(
    private val context: Context,
    private val managedVersions: ManagedVersionRepository,
    private val validator: ApkSetValidator,
    private val preferences: AppPreferences,
    private val applicationScope: CoroutineScope
) : PackageCoordinator {
    private val installer = PackageInstaller.getInstance(context)
    private val uninstaller = PackageUninstaller.getInstance(context)
    private val subscriptions = DisposableSubscriptionContainer()
    private val _installedPackage = MutableStateFlow<InstalledPackage?>(null)
    override val installedPackage: StateFlow<InstalledPackage?> = _installedPackage.asStateFlow()
    private val _operation = MutableStateFlow<PackageOperationState>(PackageOperationState.Idle)
    private data class ImportedApk(val file: File, val versionCode: Long, val versionName: String, val architecture: Architecture)
    private val importedApks = mutableMapOf<String, ImportedApk>()
    override val operation: StateFlow<PackageOperationState> = _operation.asStateFlow()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart != MINECRAFT_PACKAGE) return
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            refreshInstalledPackage()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshInstalledPackage()
        applicationScope.launch { restoreInstallerSession() }
    }

    override fun refreshInstalledPackage() {
        applicationScope.launch(Dispatchers.IO) {
            _installedPackage.value = queryInstalledPackage()
        }
    }

    override suspend fun importApk(uri: Uri) {
        if (_operation.value is PackageOperationState.Preparing || _operation.value is PackageOperationState.Installing ||
            _operation.value is PackageOperationState.Uninstalling) return
        val id = "import-${UUID.randomUUID()}"
        _operation.value = PackageOperationState.Preparing(id)
        try {
            val imported = withContext(Dispatchers.IO) {
                val directory = File(context.filesDir, "imports/$id").apply { mkdirs() }
                val apk = File(directory, "base.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 1024L * 1024L * 1024L) throw ApkValidationException("The selected APK is larger than 1 GB.")
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw ApkValidationException("The selected APK could not be opened.")
                if (!apk.isFile || apk.length() == 0L) throw ApkValidationException("The selected APK is empty.")
                val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?: throw ApkValidationException("The selected file is not a valid Android APK.")
                if (info.packageName != MINECRAFT_PACKAGE) throw ApkValidationException("This APK is not the Minecraft package.")
                val abis = ZipFile(apk).use { zip ->
                    zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }
                        .mapNotNull { it.split('/').getOrNull(1) }.toSet()
                }
                val architecture = if (abis.isEmpty()) Architecture.UNKNOWN else
                    Architecture.compatibleDeviceArchitectures().firstOrNull { it.abi in abis }
                        ?: throw ApkValidationException("This APK does not contain native libraries for this device.")
                ImportedApk(apk, info.longVersionCode, info.versionName ?: info.longVersionCode.toString(), architecture)
            }
            val installed = installedPackage.value
            if (installed != null && imported.versionCode < installed.versionCode) {
                throw ApkValidationException("This APK is older than the installed Minecraft version. Downgrading may require uninstalling Minecraft and can erase its data.")
            }
            validator.validate(listOf(imported.file), imported.versionCode, imported.architecture)
            importedApks[id] = imported
            installImportedInternal(id)
        } catch (error: Exception) {
            _operation.value = PackageOperationState.Failure(error.message ?: "The APK could not be imported.")
        }
    }

    override suspend fun install(versionId: String) {
        if (_operation.value is PackageOperationState.Preparing ||
            _operation.value is PackageOperationState.Installing ||
            _operation.value is PackageOperationState.Uninstalling
        ) return
        if (importedApks.containsKey(versionId)) { installImportedInternal(versionId); return }
        _operation.value = PackageOperationState.Preparing(versionId)
        val target = managedVersions.get(versionId)
            ?: return fail("The managed version no longer exists.")
        val installed = installedPackage.value
        if (installed != null && DowngradePolicy.requiresConfirmation(installed.versionCode, target.versionCode)) {
            _operation.value = PackageOperationState.DowngradeConfirmationRequired(
                versionId,
                installed.versionName,
                target.versionName
            )
            return
        }
        installInternal(versionId)
    }

    override suspend fun confirmDowngrade(versionId: String) {
        val target = managedVersions.get(versionId)
            ?: return fail("The target version no longer exists.")
        val installed = installedPackage.value
        if (installed == null || target.versionCode >= installed.versionCode) {
            installInternal(versionId)
            return
        }
        uninstallInternal(versionId)
    }

    override suspend fun dismissOperation() {
        _operation.value = PackageOperationState.Idle
    }

    override suspend fun uninstallMinecraft() = uninstallInternal(null)

    override fun launchMinecraft(): LaunchResult {
        val intent = context.packageManager.getLaunchIntentForPackage(MINECRAFT_PACKAGE)
            ?: return LaunchResult.Failure("Minecraft does not expose a launchable activity.")
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LaunchResult.Launched
        }.getOrElse { LaunchResult.Failure("Minecraft could not be opened on this device.") }
    }

    private suspend fun installImportedInternal(id: String) {
        val imported = importedApks[id] ?: return fail("The imported APK is no longer available. Import it again.")
        if (!context.packageManager.canRequestPackageInstalls()) {
            _operation.value = PackageOperationState.PermissionRequired(id)
            return
        }
        _operation.value = PackageOperationState.Preparing(id)
        runCatching { validator.validate(listOf(imported.file), imported.versionCode, imported.architecture) }
            .onFailure { return fail(it.message ?: "APK verification failed.") }
        val session = installer.createSession(
            InstallParameters.Builder(listOf(Uri.fromFile(imported.file)))
                .setConfirmation(Confirmation.IMMEDIATE)
                .setInstallerType(InstallerType.SESSION_BASED)
                .setName("Minecraft ${imported.versionName} (imported)")
                .build()
        )
        attachInstallerSession(session, id)
        _operation.value = PackageOperationState.Installing(id, null)
    }

    private suspend fun installInternal(versionId: String) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _operation.value = PackageOperationState.PermissionRequired(versionId)
            return
        }
        _operation.value = PackageOperationState.Preparing(versionId)
        val target = managedVersions.get(versionId) ?: return fail("The target version no longer exists.")
        val files = withContext(Dispatchers.IO) {
            versionDirectory(versionId).listFiles { file -> file.extension.equals("apk", true) }
                ?.sortedBy { it.name }
                .orEmpty()
        }
        runCatching { validator.validate(files, target.versionCode, target.architecture) }
            .onFailure { return fail(it.message ?: "APK verification failed.") }

        val session = installer.createSession(
            InstallParameters.Builder(files.map { Uri.fromFile(it) })
                .setConfirmation(Confirmation.IMMEDIATE)
                .setInstallerType(InstallerType.SESSION_BASED)
                .setName("Minecraft ${target.versionName}")
                .build()
        )
        preferences.setInstallerSession(session.id.toString(), versionId)
        attachInstallerSession(session, versionId)
        _operation.value = PackageOperationState.Installing(versionId, null)
    }

    private suspend fun uninstallInternal(continueWithVersionId: String?) {
        if (installedPackage.value == null) {
            if (continueWithVersionId != null) installInternal(continueWithVersionId)
            return
        }
        val session = uninstaller.createSession(
            UninstallParameters.Builder(MINECRAFT_PACKAGE)
                .setConfirmation(Confirmation.IMMEDIATE)
                .build()
        )
        _operation.value = PackageOperationState.Uninstalling(continueWithVersionId)
        session.addStateListener(subscriptions, object : Session.TerminalStateListener<UninstallFailure>(session) {
            override fun onSuccess(sessionId: UUID) {
                refreshInstalledPackage()
                applicationScope.launch {
                    if (continueWithVersionId != null) installInternal(continueWithVersionId)
                    else _operation.value = PackageOperationState.Completed("Minecraft was uninstalled.")
                }
            }

            override fun onFailure(sessionId: UUID, failure: UninstallFailure) {
                _operation.value = PackageOperationState.Failure("Minecraft could not be uninstalled: $failure")
            }

            override fun onCancelled(sessionId: UUID) {
                _operation.value = PackageOperationState.Idle
            }
        })
    }

    private fun attachInstallerSession(session: ProgressSession<InstallFailure>, versionId: String) {
        session.addProgressListener(subscriptions) { _, progress ->
            _operation.value = PackageOperationState.Installing(versionId, progress.progress.coerceIn(0, 100))
        }
        session.addStateListener(subscriptions, object : Session.TerminalStateListener<InstallFailure>(session) {
            override fun onSuccess(sessionId: UUID) {
                applicationScope.launch {
                    preferences.setInstallerSession(null, null)
                    refreshInstalledPackage()
                    importedApks.remove(versionId)?.file?.parentFile?.deleteRecursively()
                    _operation.value = PackageOperationState.Completed("Minecraft installation finished.")
                }
            }

            override fun onFailure(sessionId: UUID, failure: InstallFailure) {
                applicationScope.launch { preferences.setInstallerSession(null, null) }
                _operation.value = PackageOperationState.Failure(failure.message ?: "Minecraft could not be installed.")
            }

            override fun onCancelled(sessionId: UUID) {
                applicationScope.launch { preferences.setInstallerSession(null, null) }
                _operation.value = PackageOperationState.Idle
            }
        })
    }

    private suspend fun restoreInstallerSession() = withContext(Dispatchers.IO) {
        val sessionId = preferences.installerSessionId()?.let { value ->
            runCatching { UUID.fromString(value) }.getOrNull()
        } ?: return@withContext
        val versionId = preferences.installerVersionId() ?: return@withContext preferences.setInstallerSession(null, null)
        val session = runCatching { installer.getSessionAsync(sessionId).get(10, TimeUnit.SECONDS) }.getOrNull()
        if (session == null || session.isCompleted || session.isCancelled) {
            preferences.setInstallerSession(null, null)
            refreshInstalledPackage()
        } else {
            attachInstallerSession(session, versionId)
            _operation.value = PackageOperationState.Installing(versionId, null)
        }
    }

    private fun queryInstalledPackage(): InstalledPackage? = runCatching {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, flags)
        }
        val signatures = if (info.signingInfo?.hasMultipleSigners() == true) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signingInfo?.signingCertificateHistory
        }.orEmpty()
        val signer = signatures.map { sha256(it.toByteArray()) }.sorted().joinToString(":")
        val nativeDirectory = info.applicationInfo?.nativeLibraryDir.orEmpty().lowercase()
        val architecture = when {
            "arm64" in nativeDirectory -> Architecture.ARM64
            "arm" in nativeDirectory -> Architecture.ARM
            "x86_64" in nativeDirectory -> Architecture.X86_64
            "x86" in nativeDirectory -> Architecture.X86
            else -> Architecture.UNKNOWN
        }
        InstalledPackage(
            versionCode = info.longVersionCode,
            versionName = info.versionName ?: info.longVersionCode.toString(),
            signerSha256 = signer,
            architecture = architecture
        )
    }.getOrNull()

    private fun versionDirectory(id: String) = File(context.filesDir, "versions/$id")

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun fail(message: String) {
        _operation.value = PackageOperationState.Failure(message)
    }
}
