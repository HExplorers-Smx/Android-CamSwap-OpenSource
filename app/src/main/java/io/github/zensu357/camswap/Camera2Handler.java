package io.github.zensu357.camswap;

import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.File;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.zensu357.camswap.utils.VideoManager;
import io.github.zensu357.camswap.utils.PermissionHelper;
import io.github.zensu357.camswap.utils.LogUtil;

public class Camera2Handler implements ICameraHandler {

    @Override
    public void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
                String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[1] == null) {
                            return;
                        }
                        if (param.args[1].equals(HookMain.c2_state_cb)) {
                            return;
                        }
                        HookMain.c2_state_cb = (CameraDevice.StateCallback) param.args[1];
                        HookMain.c2_state_callback = param.args[1].getClass();
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }
                        VideoManager.updateVideoPath(true);
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists()) {
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        LogUtil.log("【CS】1位参数初始化相机，类：" + HookMain.c2_state_callback.toString());
                        HookMain.camera2Hook.isFirstHookBuild = true;
                        HookMain.process_camera2_init(HookMain.c2_state_callback);
                    }
                });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
                    String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args[2] == null) {
                                return;
                            }
                            if (param.args[2].equals(HookMain.c2_state_cb)) {
                                return;
                            }
                            HookMain.c2_state_cb = (CameraDevice.StateCallback) param.args[2];
                            if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                                return;
                            }
                            VideoManager.updateVideoPath(true);
                            File file = new File(VideoManager.getCurrentVideoPath());
                            HookMain.need_to_show_toast = !VideoManager.getConfig()
                                    .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                            if (!file.exists()) {
                                if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                    try {
                                        LogUtil.log("【CS】不存在替换视频: " + lpparam.packageName + " 当前路径："
                                                + VideoManager.video_path);
                                    } catch (Exception ee) {
                                        LogUtil.log("【CS】[toast]" + ee.toString());
                                    }
                                }
                                return;
                            }
                            HookMain.c2_state_callback = param.args[2].getClass();
                            LogUtil.log("【CS】2位参数初始化相机，类：" + HookMain.c2_state_callback.toString());
                            HookMain.camera2Hook.isFirstHookBuild = true;
                            HookMain.process_camera2_init(HookMain.c2_state_callback);
                        }
                    });
        }

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader,
                "addTarget", Surface.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {

                        if (param.args[0] == null) {
                            return;
                        }
                        if (param.thisObject == null) {
                            return;
                        }
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists()) {
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        if (param.args[0].equals(HookMain.camera2Hook.getVirtualSurface())) {
                            return;
                        }

                        // Check disable module
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }

                        // Dynamic defense for Photo Fake
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)
                                && HookMain.camera2Hook.isTrackedReaderSurface((Surface) param.args[0])) {
                            LogUtil.log("【CS】检测到 ImageReader Surface 在 addTarget: " + param.args[0]);
                            if (HookMain.camera2Hook.isJpegReaderSurface((Surface) param.args[0])) {
                                HookMain.camera2Hook.markPendingJpegCapture((Surface) param.args[0]);
                                LogUtil.log("【CS】保留 JPEG ImageReader 目标用于拍照: " + param.args[0]);
                                return;
                            }
                        }

                        Surface originalSurface = (Surface) param.args[0];
                        if (HookMain.camera2Hook.isTrackedReaderSurface(originalSurface)) {
                            HookMain.camera2Hook.rememberReaderPlaybackSurface(originalSurface);
                            if (HookMain.camera2Hook.shouldKeepRealReaderSurfaceForPackage(originalSurface,
                                    HookMain.toast_content != null ? HookMain.toast_content.getPackageName() : null)) {
                                LogUtil.log("【CS】保留兼容性 ImageReader 目标: " + originalSurface);
                                return;
                            }
                        } else {
                            HookMain.camera2Hook.rememberPreviewSurface(originalSurface);
                        }
                        LogUtil.log("【CS】添加目标：" + originalSurface.toString());
                        param.args[0] = HookMain.camera2Hook.getVirtualSurface();

                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader,
                "removeTarget", Surface.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {

                        if (param.args[0] == null) {
                            return;
                        }
                        if (param.thisObject == null) {
                            return;
                        }
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists()) {
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }
                        Surface rm_surf = (Surface) param.args[0];
                        HookMain.camera2Hook.onTargetRemoved(rm_surf);

                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "build",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject == null) {
                            return;
                        }
                        if (param.thisObject.equals(HookMain.camera2Hook.captureBuilder)) {
                            return;
                        }
                        HookMain.camera2Hook.captureBuilder = (CaptureRequest.Builder) param.thisObject;
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists() && HookMain.need_to_show_toast) {
                            if (HookMain.toast_content != null) {
                                try {
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }

                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }
                        LogUtil.log("【CS】开始build请求");

                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)
                                && HookMain.camera2Hook.pendingPhotoSurface != null
                                && HookMain.camera2Hook.isJpegReaderSurface(HookMain.camera2Hook.pendingPhotoSurface)) {
                            LogUtil.log("【CS】build 已标记等待 JPEG acquire 替换: "
                                    + HookMain.camera2Hook.pendingPhotoSurface);
                        }

                        HookMain.process_camera2_play();
                    }
                });
    }
}
