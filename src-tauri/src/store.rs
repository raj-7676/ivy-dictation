//! Ivy from the Microsoft Store (MSIX, Yash 2026-10-17). The same exe runs in both builds; these helpers tell
//! them apart at runtime. Inside a package a few things differ from the GitHub setup build:
//! - Updates come from the Store, so update.rs refuses to check or download (Store policy).
//! - Writes to the HKCU Run key are virtualized into the package and never reach Windows, so "Launch at
//!   startup" uses the manifest's StartupTask ("IvyStartup", msix/AppxManifest.xml) instead.
//! - A StartupTask launch carries no `--autostart` argument; it is recognized by its activation kind.
//! - Windows asks the user about the microphone for packaged apps; Ivy triggers that prompt at launch.
//! The model ships inside the package next to the exe, so models_dir() finds it like a bundled install.

#[cfg(windows)]
const STARTUP_TASK: &str = "IvyStartup";

/// True when running from an installed MSIX package (the Store build).
#[cfg(windows)]
pub fn is_packaged() -> bool {
    use std::sync::OnceLock;
    static PACKAGED: OnceLock<bool> = OnceLock::new();
    *PACKAGED.get_or_init(|| {
        use windows::Win32::Foundation::APPMODEL_ERROR_NO_PACKAGE;
        use windows::Win32::Storage::Packaging::Appx::GetCurrentPackageFullName;
        let mut len = 0u32;
        // With a zero-length buffer a packaged process answers ERROR_INSUFFICIENT_BUFFER, an unpackaged one
        // APPMODEL_ERROR_NO_PACKAGE.
        unsafe { GetCurrentPackageFullName(&mut len, windows::core::PWSTR::null()) != APPMODEL_ERROR_NO_PACKAGE }
    })
}

#[cfg(not(windows))]
pub fn is_packaged() -> bool {
    false
}

/// Turns the package's StartupTask on or off. Best-effort, like the Run-key path: a user who turned Ivy off in
/// Task Manager > Startup apps (DisabledByUser) can only turn it back on there, and Windows won't let an app
/// override that.
#[cfg(windows)]
pub fn set_startup_task(enabled: bool) {
    use windows::ApplicationModel::{StartupTask, StartupTaskState};
    // RequestEnableAsync is awaited with a blocking get(), so keep it off the UI thread.
    std::thread::spawn(move || {
        let result = (|| -> windows::core::Result<StartupTaskState> {
            let task = StartupTask::GetAsync(&STARTUP_TASK.into())?.get()?;
            if enabled {
                task.RequestEnableAsync()?.get()
            } else {
                task.Disable()?;
                task.State()
            }
        })();
        match result {
            Ok(state) => log::info!("Ivy: startup task now {state:?}"),
            Err(e) => log::warn!("Ivy: couldn't change the startup task: {e}"),
        }
    });
}

/// Store build: if Windows hasn't asked about the microphone yet, ask now. A packaged app gets Windows' own
/// consent prompt; answered "No", dictation fails with E_ACCESSDENIED until the user flips Settings >
/// Privacy & security > Microphone (lib.rs then says so and opens that page).
#[cfg(windows)]
pub fn request_microphone() {
    use windows::Security::Authorization::AppCapabilityAccess::{AppCapability, AppCapabilityAccessStatus};
    if !is_packaged() {
        return;
    }
    std::thread::spawn(|| {
        let result = (|| -> windows::core::Result<AppCapabilityAccessStatus> {
            let mic = AppCapability::Create(&"microphone".into())?;
            let status = mic.CheckAccess()?;
            if status != AppCapabilityAccessStatus::UserPromptRequired {
                return Ok(status);
            }
            mic.RequestAccessAsync()?.get()
        })();
        match result {
            Ok(status) => log::info!("Ivy: microphone access {status:?}"),
            Err(e) => log::warn!("Ivy: couldn't ask for microphone access: {e}"),
        }
    });
}

/// True when Windows started the package at sign-in through the StartupTask.
#[cfg(windows)]
pub fn launched_by_startup_task() -> bool {
    use windows::ApplicationModel::Activation::ActivationKind;
    use windows::ApplicationModel::AppInstance;
    is_packaged()
        && AppInstance::GetActivatedEventArgs()
            .and_then(|a| a.Kind())
            .map_or(false, |k| k == ActivationKind::StartupTask)
}

#[cfg(not(windows))]
pub fn launched_by_startup_task() -> bool {
    false
}

/// Ivy Dictation's Store ID (Partner Center, reserved 2026-10-17).
#[cfg(windows)]
const STORE_PRODUCT_ID: &str = "9P420N80LXQJ";

/// Opens Ivy's page in the Microsoft Store app (Settings > Updates in the Store build), where the user can see
/// or force an update; the Store also updates Ivy by itself.
#[tauri::command]
pub fn open_store_page() {
    // ShellExecute, like a clicked link: handing this "?" URL to explorer.exe opened Documents instead.
    #[cfg(windows)]
    unsafe {
        use windows::core::{w, HSTRING, PCWSTR};
        use windows::Win32::Foundation::HWND;
        use windows::Win32::UI::Shell::ShellExecuteW;
        use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
        let url = HSTRING::from(format!("ms-windows-store://pdp/?ProductId={STORE_PRODUCT_ID}"));
        ShellExecuteW(HWND::default(), w!("open"), &url, PCWSTR::null(), PCWSTR::null(), SW_SHOWNORMAL);
    }
}

/// Lets the Settings page swap "Check for updates" for a note that the Store updates Ivy.
#[tauri::command]
pub fn is_store_app() -> bool {
    is_packaged()
}
