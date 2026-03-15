package io.github.zensu357.camswap;

import android.graphics.SurfaceTexture;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.util.ArrayList;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Map;
import android.media.ImageWriter;
import android.media.Image;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Handles Camera2 session interception: replaces real camera surfaces with
 * a virtual surface, then starts video playback via {@link MediaPlayerManager}.
 */
public final class Camera2SessionHook {
    private final MediaPlayerManager playerManager;

    // Camera2 surfaces
    Surface previewSurface;
    Surface previewSurface1;
    Surface readerSurface;
    Surface readerSurface1;

    // Tracker for Photo Fake
    public final Set<Surface> trackedReaderSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    public final Map<Surface, ImageWriter> imageWriterMap = new ConcurrentHashMap<>();
    public final Map<Surface, Integer> surfaceFormatMap = new ConcurrentHashMap<>();
    public final Map<Surface, int[]> surfaceSizeMap = new ConcurrentHashMap<>();
    public final Set<Surface> pendingJpegSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    private final Set<String> hookedStateCallbackClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> hookedDeviceClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> hookedSessionCallbackClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // Photo Fake: 等待 build() 时触发
    public volatile Surface pendingPhotoSurface;

    // Virtual surface for session hijacking
    private Surface virtualSurface;
    private SurfaceTexture virtualTexture;
    private boolean needRecreate;

    /** Public accessor for Camera2Handler to check/redirect surfaces. */
    public Surface getVirtualSurface() {
        return virtualSurface;
    }

    // Session config
    CaptureRequest.Builder captureBuilder;
    SessionConfiguration fakeSessionConfig;
    SessionConfiguration realSessionConfig;
    OutputConfiguration outputConfig;
    boolean isFirstHookBuild = true;

    public Camera2SessionHook(MediaPlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    private boolean isWhatsAppPackage(String packageName) {
        return packageName != null && packageName.toLowerCase(Locale.ROOT).contains("whatsapp");
    }

    private String getCurrentPackageName() {
        return HookMain.toast_content != null ? HookMain.toast_content.getPackageName() : null;
    }

    /** Called by Camera2Handler when onOpened fires on the state callback class. */
    public void hookStateCallback(Class<?> hookedClass) {
        if (hookedClass == null) {
            return;
        }
        if (!hookedStateCallbackClasses.add(hookedClass.getName())) {
            return;
        }
        XposedHelpers.findAndHookMethod(hookedClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                needRecreate = true;
                createVirtualSurface();
                playerManager.releaseCamera2Resources();
                releaseImageWriters();
                previewSurface1 = null;
                readerSurface1 = null;
                readerSurface = null;
                previewSurface = null;
                isFirstHookBuild = true;
                LogUtil.log("【CS】打开相机C2");

                File file = new File(VideoManager.getCurrentVideoPath());
                boolean showToast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                if (!file.exists()) {
                    if (HookMain.toast_content != null && showToast) {
                        try {
                            LogUtil.log("【CS】不存在替换视频: " + HookMain.toast_content.getPackageName()
                                    + " 当前路径：" + VideoManager.video_path);
                        } catch (Exception ee) {
                            LogUtil.log("【CS】[toast]" + ee);
                        }
                    }
                    return;
                }

                hookAllCreateSessionVariants(param.args[0].getClass());
            }
        });

        XposedHelpers.findAndHookMethod(hookedClass, "onClosed", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                LogUtil.log("【CS】相机关闭 onClosed，释放播放器资源");
                playerManager.releaseCamera2Resources();
                releaseImageWriters();
            }
        });

        XposedHelpers.findAndHookMethod(hookedClass, "onError", CameraDevice.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                LogUtil.log("【CS】相机错误onerror：" + (int) param.args[1]);
            }
        });

        XposedHelpers.findAndHookMethod(hookedClass, "onDisconnected", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                LogUtil.log("【CS】相机断开 onDisconnected，释放播放器资源");
                playerManager.releaseCamera2Resources();
                releaseImageWriters();
            }
        });
    }

    /** Start video playback on all current surfaces. */
    public void startPlayback() {
        playerManager.initCamera2Players(readerSurface, readerSurface1,
                previewSurface, previewSurface1);
    }

    public void registerImageReaderSurface(Surface surface, int format, int width, int height) {
        if (surface == null) {
            return;
        }
        trackedReaderSurfaces.add(surface);
        surfaceFormatMap.put(surface, format);
        surfaceSizeMap.put(surface, new int[] { width, height });
    }

    public boolean isTrackedReaderSurface(Surface surface) {
        return surface != null && trackedReaderSurfaces.contains(surface);
    }

    public boolean isJpegReaderSurface(Surface surface) {
        Integer format = surfaceFormatMap.get(surface);
        return format != null && format == ImageFormat.JPEG;
    }

    public boolean shouldKeepRealReaderSurface(Surface surface) {
        return isJpegReaderSurface(surface);
    }

    public boolean shouldKeepRealReaderSurfaceForPackage(Surface surface, String packageName) {
        if (!isTrackedReaderSurface(surface)) {
            return false;
        }
        if (isJpegReaderSurface(surface)) {
            return true;
        }
        if (packageName != null && packageName.toLowerCase(Locale.ROOT).contains("whatsapp")) {
            return true;
        }
        return false;
    }

    public boolean shouldUseReaderPlaybackSurfaceForPackage(String packageName) {
        return !isWhatsAppPackage(packageName);
    }

    public void rememberPreviewSurface(Surface surface) {
        if (surface == null || isTrackedReaderSurface(surface)) {
            return;
        }
        if (previewSurface == null) {
            previewSurface = surface;
        } else if (!previewSurface.equals(surface) && previewSurface1 == null) {
            previewSurface1 = surface;
        }
    }

    public void rememberReaderPlaybackSurface(Surface surface) {
        if (!shouldUseReaderPlaybackSurfaceForPackage(getCurrentPackageName())) {
            return;
        }
        if (surface == null || shouldKeepRealReaderSurface(surface)) {
            return;
        }
        if (readerSurface == null) {
            readerSurface = surface;
        } else if (!readerSurface.equals(surface) && readerSurface1 == null) {
            readerSurface1 = surface;
        }
    }

    public void onTargetRemoved(Surface surface) {
        if (surface == null) {
            return;
        }
        pendingJpegSurfaces.remove(surface);
        if (surface.equals(previewSurface)) {
            previewSurface = null;
        }
        if (surface.equals(previewSurface1)) {
            previewSurface1 = null;
        }
        if (surface.equals(readerSurface)) {
            readerSurface = null;
        }
        if (surface.equals(readerSurface1)) {
            readerSurface1 = null;
        }
    }

    public void markPendingJpegCapture(Surface surface) {
        if (shouldKeepRealReaderSurface(surface)) {
            pendingJpegSurfaces.add(surface);
            pendingPhotoSurface = surface;
        }
    }

    // =====================================================================
    // Virtual surface management
    // =====================================================================

    private Surface createVirtualSurface() {
        if (needRecreate) {
            if (virtualTexture != null) {
                virtualTexture.release();
                virtualTexture = null;
            }
            if (virtualSurface != null) {
                virtualSurface.release();
                virtualSurface = null;
            }
            virtualTexture = new SurfaceTexture(15);
            virtualSurface = new Surface(virtualTexture);
            needRecreate = false;
        } else {
            if (virtualSurface == null) {
                needRecreate = true;
                virtualSurface = createVirtualSurface();
            }
        }
        LogUtil.log("【CS】【重建虚拟Surface】" + virtualSurface);
        return virtualSurface;
    }

    private Surface getSessionSurface(Surface surface) {
        if (shouldKeepRealReaderSurface(surface)) {
            return surface;
        }
        return createVirtualSurface();
    }

    private Surface getSessionSurface(Surface surface, String packageName) {
        if (shouldKeepRealReaderSurfaceForPackage(surface, packageName)) {
            return surface;
        }
        return createVirtualSurface();
    }

    private List<Surface> rewriteSessionSurfaces(List<?> outputs) {
        return rewriteSessionSurfaces(outputs, getCurrentPackageName());
    }

    private List<Surface> rewriteSessionSurfaces(List<?> outputs, String packageName) {
        LinkedHashSet<Surface> rewritten = new LinkedHashSet<>();
        if (outputs != null) {
            for (Object output : outputs) {
                if (output instanceof Surface) {
                    rewritten.add(getSessionSurface((Surface) output, packageName));
                }
            }
        }
        if (rewritten.isEmpty()) {
            rewritten.add(createVirtualSurface());
        }
        return new ArrayList<>(rewritten);
    }

    private OutputConfiguration createOutputConfiguration(OutputConfiguration original, Surface surface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return new OutputConfiguration(original.getSurfaceGroupId(), surface);
            } catch (Throwable ignored) {
            }
        }
        return new OutputConfiguration(surface);
    }

    private List<OutputConfiguration> rewriteOutputConfigurations(List<?> outputs) {
        return rewriteOutputConfigurations(outputs, getCurrentPackageName());
    }

    private List<OutputConfiguration> rewriteOutputConfigurations(List<?> outputs, String packageName) {
        List<OutputConfiguration> rewritten = new ArrayList<>();
        boolean hasVirtualOutput = false;
        if (outputs != null) {
            for (Object output : outputs) {
                if (!(output instanceof OutputConfiguration)) {
                    continue;
                }
                OutputConfiguration config = (OutputConfiguration) output;
                Surface originalSurface = config.getSurface();
                if (shouldKeepRealReaderSurfaceForPackage(originalSurface, packageName)) {
                    rewritten.add(createOutputConfiguration(config, originalSurface));
                } else if (!hasVirtualOutput) {
                    rewritten.add(createOutputConfiguration(config, createVirtualSurface()));
                    hasVirtualOutput = true;
                }
            }
        }
        if (rewritten.isEmpty()) {
            rewritten.add(new OutputConfiguration(createVirtualSurface()));
        }
        return rewritten;
    }

    private SessionConfiguration rewriteSessionConfiguration(SessionConfiguration sessionConfiguration) {
        String packageName = getCurrentPackageName();
        List<OutputConfiguration> outputs = rewriteOutputConfigurations(sessionConfiguration.getOutputConfigurations(), packageName);
        SessionConfiguration rewritten = new SessionConfiguration(
                sessionConfiguration.getSessionType(),
                outputs,
                sessionConfiguration.getExecutor(),
                sessionConfiguration.getStateCallback());
        try {
            InputConfiguration inputConfiguration = sessionConfiguration.getInputConfiguration();
            if (inputConfiguration != null) {
                rewritten.setInputConfiguration(inputConfiguration);
            }
        } catch (Throwable ignored) {
        }
        try {
            CaptureRequest sessionParameters = sessionConfiguration.getSessionParameters();
            if (sessionParameters != null) {
                rewritten.setSessionParameters(sessionParameters);
            }
        } catch (Throwable ignored) {
        }
        return rewritten;
    }

    // =====================================================================
    // Hook all createCaptureSession variants
    // =====================================================================

    private void hookAllCreateSessionVariants(Class<?> deviceClass) {
        if (deviceClass == null) {
            return;
        }
        if (!hookedDeviceClasses.add(deviceClass.getName())) {
            return;
        }
        // 1. createCaptureSession(List, StateCallback, Handler)
        XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List.class,
                CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[0] != null) {
                                LogUtil.log("【CS】createCaptureSession创建捕获，原始:" + p.args[0] + "虚拟：" + virtualSurface);
                                p.args[0] = rewriteSessionSurfaces((List<?>) p.args[0]);
                                if (p.args[1] != null)
                                    hookSessionCallback((CameraCaptureSession.StateCallback) p.args[1]);
                            }
                        }
                });

        // 2. createCaptureSessionByOutputConfigurations (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            XposedHelpers.findAndHookMethod(deviceClass,
                    "createCaptureSessionByOutputConfigurations", List.class,
                    CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[0] != null) {
                                p.args[0] = rewriteOutputConfigurations((List<?>) p.args[0]);
                                LogUtil.log("【CS】执行了createCaptureSessionByOutputConfigurations");
                                if (p.args[1] != null)
                                    hookSessionCallback((CameraCaptureSession.StateCallback) p.args[1]);
                            }
                        }
                    });
        }

        // 3. createConstrainedHighSpeedCaptureSession
        XposedHelpers.findAndHookMethod(deviceClass, "createConstrainedHighSpeedCaptureSession",
                List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args[0] != null) {
                            p.args[0] = rewriteSessionSurfaces((List<?>) p.args[0]);
                            LogUtil.log("【CS】执行了 createConstrainedHighSpeedCaptureSession");
                            if (p.args[1] != null)
                                hookSessionCallback((CameraCaptureSession.StateCallback) p.args[1]);
                        }
                    }
                });

        // 4. createReprocessableCaptureSession (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSession",
                    InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class,
                    Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[1] != null) {
                                p.args[1] = rewriteSessionSurfaces((List<?>) p.args[1]);
                                LogUtil.log("【CS】执行了 createReprocessableCaptureSession ");
                                if (p.args[2] != null)
                                    hookSessionCallback((CameraCaptureSession.StateCallback) p.args[2]);
                            }
                        }
                    });
        }

        // 5. createReprocessableCaptureSessionByConfigurations (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            XposedHelpers.findAndHookMethod(deviceClass,
                    "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class, List.class,
                    CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[1] != null) {
                                p.args[1] = rewriteOutputConfigurations((List<?>) p.args[1]);
                                LogUtil.log("【CS】执行了 createReprocessableCaptureSessionByConfigurations");
                                if (p.args[2] != null)
                                    hookSessionCallback((CameraCaptureSession.StateCallback) p.args[2]);
                            }
                        }
                    });
        }

        // 6. createCaptureSession(SessionConfiguration) (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession",
                    SessionConfiguration.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[0] != null) {
                                LogUtil.log("【CS】执行了 createCaptureSession (SessionConfiguration)");
                                realSessionConfig = (SessionConfiguration) p.args[0];
                                fakeSessionConfig = rewriteSessionConfiguration(realSessionConfig);
                                p.args[0] = fakeSessionConfig;
                                hookSessionCallback(realSessionConfig.getStateCallback());
                            }
                        }
                    });
        }
    }

    // =====================================================================
    // Session callback logging hooks
    // =====================================================================

    private void hookSessionCallback(CameraCaptureSession.StateCallback cb) {
        if (cb == null)
            return;
        if (!hookedSessionCallbackClasses.add(cb.getClass().getName())) {
            return;
        }
        XposedHelpers.findAndHookMethod(cb.getClass(), "onConfigureFailed", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        LogUtil.log("【CS】onConfigureFailed ：" + p.args[0]);
                    }
                });
        XposedHelpers.findAndHookMethod(cb.getClass(), "onConfigured", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        LogUtil.log("【CS】onConfigured ：" + p.args[0]);
                    }
                });
        XposedHelpers.findAndHookMethod(cb.getClass(), "onClosed", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        LogUtil.log("【CS】onClosed ：" + p.args[0]);
                    }
                });
    }

    public void releaseImageWriters() {
        for (ImageWriter writer : imageWriterMap.values()) {
            try {
                writer.close();
            } catch (Exception e) {
                LogUtil.log("【CS】关闭 ImageWriter 失败: " + e);
            }
        }
        imageWriterMap.clear();
        trackedReaderSurfaces.clear();
        surfaceFormatMap.clear();
        surfaceSizeMap.clear();
        pendingJpegSurfaces.clear();
        pendingPhotoSurface = null;
    }

    /** 获取当前活跃的 GLVideoRenderer（优先 preview，其次 reader） */
    public GLVideoRenderer getActiveRenderer() {
        MediaPlayerManager pm = HookMain.playerManager;
        if (pm.c2_renderer != null && pm.c2_renderer.isInitialized())
            return pm.c2_renderer;
        if (pm.c2_renderer_1 != null && pm.c2_renderer_1.isInitialized())
            return pm.c2_renderer_1;
        if (pm.c2_reader_renderer != null && pm.c2_reader_renderer.isInitialized())
            return pm.c2_reader_renderer;
        if (pm.c2_reader_renderer_1 != null && pm.c2_reader_renderer_1.isInitialized())
            return pm.c2_reader_renderer_1;
        return null;
    }

    private byte[] createFakeJpegBytes(Surface targetSurface, int maxBytes) {
        int targetWidth = HookMain.c2_ori_width;
        int targetHeight = HookMain.c2_ori_height;
        int[] size = surfaceSizeMap.get(targetSurface);
        if (size != null && size.length >= 2) {
            targetWidth = size[0];
            targetHeight = size[1];
        }
        if (targetWidth <= 0 || targetHeight <= 0) {
            targetWidth = 1280;
            targetHeight = 720;
        }

        Bitmap frame = captureFrameForStill(targetWidth, targetHeight);
        if (frame == null) {
            LogUtil.log("【CS】无法获取可用静态帧");
            return null;
        }

        return compressBitmapToJpeg(frame, maxBytes);
    }

    private Bitmap captureFrameForStill(int targetWidth, int targetHeight) {
        GLVideoRenderer activeRenderer = getActiveRenderer();
        if (activeRenderer != null) {
            int captureWidth = activeRenderer.getSurfaceWidth();
            int captureHeight = activeRenderer.getSurfaceHeight();
            if (captureWidth <= 0 || captureHeight <= 0) {
                captureWidth = targetWidth;
                captureHeight = targetHeight;
            }

            Bitmap frame = activeRenderer.captureFrame(captureWidth, captureHeight);
            if (frame != null) {
                frame = fitBitmapToTargetAspect(frame, targetWidth, targetHeight);
                if (!isBitmapMostlyBlack(frame)) {
                    return frame;
                }
                LogUtil.log("【CS】GL 截帧过黑，回退到视频文件截帧");
                frame.recycle();
            } else {
                LogUtil.log("【CS】GL 截帧返回 null，回退到视频文件截帧");
            }
        } else {
            LogUtil.log("【CS】无可用 GL 渲染器，回退到视频文件截帧");
        }

        return captureFrameFromVideoFile(targetWidth, targetHeight);
    }

    private Bitmap captureFrameFromVideoFile(int targetWidth, int targetHeight) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            if (pfd != null) {
                try {
                    retriever.setDataSource(pfd.getFileDescriptor());
                } finally {
                    try {
                        pfd.close();
                    } catch (Exception ignored) {
                    }
                }
            } else {
                retriever.setDataSource(VideoManager.getCurrentVideoPath());
            }

            long positionUs = HookMain.playerManager.getCamera2PlaybackPositionMs() * 1000L;
            Bitmap frame = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null && positionUs > 0) {
                frame = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                return null;
            }
            return fitBitmapToTargetAspect(frame, targetWidth, targetHeight);
        } catch (Exception e) {
            LogUtil.log("【CS】视频文件截帧失败: " + e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private Bitmap fitBitmapToTargetAspect(Bitmap source, int targetWidth, int targetHeight) {
        if (source == null) {
            return null;
        }
        if (targetWidth <= 0 || targetHeight <= 0) {
            return source;
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return source;
        }

        float sourceAspect = (float) sourceWidth / (float) sourceHeight;
        float targetAspect = (float) targetWidth / (float) targetHeight;

        Rect cropRect;
        if (Math.abs(sourceAspect - targetAspect) < 0.001f) {
            cropRect = new Rect(0, 0, sourceWidth, sourceHeight);
        } else if (sourceAspect > targetAspect) {
            int croppedWidth = Math.max(1, Math.round(sourceHeight * targetAspect));
            int left = Math.max(0, (sourceWidth - croppedWidth) / 2);
            cropRect = new Rect(left, 0, Math.min(sourceWidth, left + croppedWidth), sourceHeight);
        } else {
            int croppedHeight = Math.max(1, Math.round(sourceWidth / targetAspect));
            int top = Math.max(0, (sourceHeight - croppedHeight) / 2);
            cropRect = new Rect(0, top, sourceWidth, Math.min(sourceHeight, top + croppedHeight));
        }

        Bitmap cropped = source;
        if (cropRect.left != 0 || cropRect.top != 0 || cropRect.width() != sourceWidth || cropRect.height() != sourceHeight) {
            cropped = Bitmap.createBitmap(source, cropRect.left, cropRect.top, cropRect.width(), cropRect.height());
            source.recycle();
        }

        if (cropped.getWidth() == targetWidth && cropped.getHeight() == targetHeight) {
            return cropped;
        }

        Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true);
        if (scaled != cropped) {
            cropped.recycle();
        }
        return scaled;
    }

    private byte[] compressBitmapToJpeg(Bitmap frame, int maxBytes) {
        if (frame == null) {
            return null;
        }

        int[] qualities = { 92, 80, 65, 50 };
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] jpegBytes = null;
        try {
            for (int quality : qualities) {
                baos.reset();
                frame.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                byte[] candidate = baos.toByteArray();
                if (maxBytes <= 0 || candidate.length <= maxBytes) {
                    jpegBytes = candidate;
                    break;
                }
            }
        } finally {
            frame.recycle();
        }

        if (jpegBytes == null) {
            LogUtil.log("【CS】照片压缩后依然大于 Buffer 容量: " + maxBytes);
        }
        return jpegBytes;
    }

    private boolean isBitmapMostlyBlack(Bitmap bitmap) {
        if (bitmap == null) {
            return true;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return true;
        }
        int stepX = Math.max(1, width / 8);
        int stepY = Math.max(1, height / 8);
        int samples = 0;
        int darkSamples = 0;
        for (int y = stepY / 2; y < height; y += stepY) {
            for (int x = stepX / 2; x < width; x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                int brightness = (r + g + b) / 3;
                samples++;
                if (brightness < 16) {
                    darkSamples++;
                }
            }
        }
        return samples == 0 || darkSamples * 100 / samples >= 85;
    }

    public boolean replaceJpegImageIfNeeded(Object imageReader, Image image) {
        if (imageReader == null || image == null) {
            return false;
        }
        try {
            Surface surface = (Surface) XposedHelpers.callMethod(imageReader, "getSurface");
            if (!shouldKeepRealReaderSurface(surface) || !pendingJpegSurfaces.contains(surface)) {
                return false;
            }
            if (image.getPlanes() == null || image.getPlanes().length == 0) {
                LogUtil.log("【CS】JPEG Image 无可写 Plane，放弃替换");
                return false;
            }
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            if (buffer == null) {
                LogUtil.log("【CS】JPEG Plane Buffer 为 null，放弃替换");
                return false;
            }
            if (buffer.isReadOnly()) {
                LogUtil.log("【CS】JPEG Plane Buffer 只读，放弃替换");
                return false;
            }

            byte[] jpegBytes = createFakeJpegBytes(surface, buffer.capacity());
            if (jpegBytes == null || jpegBytes.length == 0) {
                return false;
            }

            buffer.clear();
            buffer.put(jpegBytes);
            buffer.flip();
            pendingJpegSurfaces.remove(surface);
            pendingPhotoSurface = null;
            LogUtil.log("【CS】已替换 JPEG ImageReader 输出: " + surface + " 大小=" + jpegBytes.length);
            return true;
        } catch (Exception e) {
            LogUtil.log("【CS】替换 JPEG Image 失败: " + e);
            return false;
        }
    }

    public void createOrPumpImage(Surface targetSurface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return;
        try {
            // 仅作为旧路径回退：新的 JPEG 替换逻辑在 acquireNextImage/acquireLatestImage 上完成。
            ImageWriter writer = imageWriterMap.get(targetSurface);
            if (writer == null) {
                writer = ImageWriter.newInstance(targetSurface, 2);
                imageWriterMap.put(targetSurface, writer);
            }
            Image image = writer.dequeueInputImage();
            if (image == null)
                return;

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] jpegBytes = createFakeJpegBytes(targetSurface, buffer.capacity());
            if (jpegBytes == null) {
                image.close();
                return;
            }
            buffer.clear();
            buffer.put(jpegBytes);
            buffer.flip();
            writer.queueInputImage(image);
            LogUtil.log("【CS】成功泵入一张伪造图片 (" + jpegBytes.length + " bytes)");
        } catch (Exception e) {
            LogUtil.log("【CS】照片注入失败: " + e);
        }
    }
}
