# Lynk & Co (L946 / DHU1DS1) — diagnostics  Mon Jul 20 21:09:21 AST 2026

## symptom
Leopard 'set wallpaper' on an IMAGE reports success, screen does not change.
No exception: applyStill() returned true, so Flyme accepted the write and ignored it.

## device
LYNK&CO
L946
12
32
23523155

## identity
VIN (settings global geely_gpt_vin): L6T79END2ST248069
wlan0 mac: 
NOTE: no VIN system property exists on this ROM; Car API INFO_VIN needs
android.car.permission.CAR_IDENTIFICATION = signature|privileged (unobtainable).

## users
Users:
	UserInfo{0:Driver:813} running
	UserInfo{10:Guest:414}
	UserInfo{11:10_clone:1010}
	UserInfo{12:Co客UWwtpn:412} running
	UserInfo{13:12_clone:1010} running
current=12

## our app
    versionCode=128 minSdk=21 targetSdk=36
    versionName=2.4
    firstInstallTime=2026-07-20 19:58:59
    lastUpdateTime=2026-07-20 19:58:59
      android.permission.SYSTEM_ALERT_WINDOW
      android.permission.SET_WALLPAPER
      android.permission.SYSTEM_ALERT_WINDOW: granted=false
      android.permission.SYSTEM_ALERT_WINDOW: granted=true, userId=12
      android.permission.SET_WALLPAPER: granted=true

## wallpaper subsystem
mDefaultWallpaperComponent=null
mImageWallpaper=ComponentInfo{com.flyme.auto.wallpaperlauncher/com.flyme.auto.wallpaper.WallpaperLauncher}
System wallpaper state:
 User 0: id=2
 Display state:
  displayId=0
  mWidth=5120  mHeight=5120
  mPadding=Rect(0, 0 - 0, 0)
  mCropHint=Rect(0, 0 - 0, 0)
  mName=
  mAllowBackup=true
  mWallpaperComponent=null
 User 12: id=9
 Display state:
  displayId=0
  mWidth=5120  mHeight=5120
  mPadding=Rect(0, 0 - 0, 0)
  mCropHint=Rect(0, 0 - 0, 0)
  mName=
  mAllowBackup=true
  mWallpaperComponent=ComponentInfo{com.flyme.auto.wallpaperlauncher/com.flyme.auto.wallpaper.WallpaperLauncher}
  Wallpaper connection com.android.server.wallpaper.WallpaperManagerService$WallpaperConnection@ca3c573:
     mDisplayId=0
     mToken=android.os.Binder@b8b7148
     mEngine=android.service.wallpaper.IWallpaperEngine$Stub$Proxy@5421967

## flyme wallpaper services (candidate live-wallpaper hosts)
        324fb90 com.flyme.auto.wallpaperlauncher/com.flyme.auto.wallpaper.PermissionActivity filter cc52c89
        e7be48e com.flyme.auto.wallpaperlauncher/androidx.profileinstaller.ProfileInstallReceiver filter 9cb0845
        e7be48e com.flyme.auto.wallpaperlauncher/androidx.profileinstaller.ProfileInstallReceiver filter e37c4af
        e7be48e com.flyme.auto.wallpaperlauncher/androidx.profileinstaller.ProfileInstallReceiver filter a0640bc
        e7be48e com.flyme.auto.wallpaperlauncher/androidx.profileinstaller.ProfileInstallReceiver filter 255bb9a
        c5fccb com.flyme.auto.wallpaperlauncher/com.flyme.auto.wallpaper.WallpaperLauncher filter 754aca8 permission android.permission.BIND_WALLPAPER
        d98e3c1 com.flyme.auto.wallpaperlauncher/com.flyme.auto.wallpaper.WallpaperLauncherClone filter 7c3f766 permission android.permission.BIND_WALLPAPER
        2b59aa7 com.flyme.auto.wallpaperlauncher/com.flyme.auto.wallpaper.WallpaperLauncherNewProcess filter 9beb54 permission android.permission.BIND_WALLPAPER
        b0efafd com.flyme.auto.wallpaperlauncher/com.flyme.alive.CosmicWallpaper filter 28823f2 permission android.permission.BIND_WALLPAPER
        e173a43 com.flyme.auto.wallpaperlauncher/com.flyme.alivewallpaper.bomb.WordBombWallpaper filter 67668c0 permission android.permission.BIND_WALLPAPER
        8d549f9 com.flyme.auto.wallpaperlauncher/com.flyme.auto.videowallpaper.VideoWallpaper filter ef08d3e permission android.permission.BIND_WALLPAPER
        369379f com.flyme.auto.wallpaperlauncher/com.flyme.auto.growwallpaper.GrowWallpaper filter 6b950ec permission android.permission.BIND_WALLPAPER

---

## Diagnosis (confirmed)

The wallpaper record for user 12 was `id=7` on the first dump and `id=9` after two
"set wallpaper" attempts. That counter increments on every accepted wallpaper write.

So the platform ACCEPTED our bitmap both times. `setBitmap()` is not failing — the write
lands, `applyStill()` correctly returns true, and the UI correctly says "done".

What does not happen is the DRAWING. `mImageWallpaper` on this ROM is
`com.flyme.auto.wallpaperlauncher/WallpaperLauncher` instead of the AOSP
`com.android.systemui/ImageWallpaper`. Flyme substituted its own component as the renderer of
static wallpapers, and that component paints its own selection (from the Flyme wallpaper app)
rather than the AOSP wallpaper file. The stored bitmap is simply never shown.

Nothing we can pass to `WallpaperManager.setBitmap()` will change this: the API contract ends
at "the bitmap is stored", and the OEM owns everything after that point.

## The fix

Stop asking the ROM to draw our picture and draw it ourselves.

`MediaWallpaperService` (already shipping, already proven for video on BYD) renders **images**
too — `MediaWallpaperService.java:146` takes the type from `contentResolver.getType()` and the
image branch decodes to a Bitmap and paints it on the surface canvas. When it is the active
live wallpaper, the system binds OUR service, Flyme's renderer is out of the loop entirely, and
what we paint is what appears.

The Flyme launcher already requests the wallpaper behind it (`mWallpaperTarget =
PsdLauncherActivity`), so a live wallpaper we own will be visible.

Route images through the live-wallpaper path, exactly like video:
  - our service already active -> instant and silent (RESULT_APPLIED_LIVE)
  - not yet active            -> the one-time system confirmation screen, then instant forever

Cost: the one-time system screen for images on ROMs where it is not already active. On BYD,
images currently work statically and also cover the lock screen, which the live path cannot —
so this should not be forced everywhere blindly. Decide between:
  a) Leopard always uses the live path (simplest, consistent, one-time cost on every car)
  b) keep static, add an explicit "الصورة مظهرتش؟" fallback that switches this car to live
  c) per-ROM: prefer live when mImageWallpaper is not the AOSP one — NOT detectable from an
     ordinary app, so this would have to be a manual/ROM-keyed setting

Not yet decided. (a) is the least code and the least explaining; (b) keeps BYD's lock-screen
behaviour intact.

## Still unknown
- Whether our live wallpaper actually renders correctly on this ROM (untested).
- Correct bitmap size: the wallpaper surface is 5120x5120 while the visible panel is 5120x1600
  and our images are 1920x720, so expect heavy upscaling and a wrong aspect until this is sized
  deliberately.
