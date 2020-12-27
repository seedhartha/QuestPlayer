package com.qsp.player.game.libqsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import com.qsp.player.JniResult;
import com.qsp.player.R;
import com.qsp.player.game.AudioPlayer;
import com.qsp.player.game.ImageProvider;
import com.qsp.player.game.PlayerView;
import com.qsp.player.game.PlayerViewState;
import com.qsp.player.game.QspListItem;
import com.qsp.player.game.QspMenuItem;
import com.qsp.player.game.WindowType;
import com.qsp.player.util.FileUtil;
import com.qsp.player.util.HtmlUtil;
import com.qsp.player.util.StreamUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

public class LibQspProxyImpl implements LibQspProxy {
    private static final Logger logger = LoggerFactory.getLogger(LibQspProxyImpl.class);

    private final Handler counterHandler = new Handler();
    private final ReentrantLock libQspLock = new ReentrantLock();
    private final PlayerViewState viewState = new PlayerViewState();
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private final Context context;
    private final SharedPreferences settings;
    private final ImageProvider imageProvider;

    private final Runnable counterTask = new Runnable() {
        @Override
        public void run() {
            runOnQspThread(() -> {
                if (!QSPExecCounter(true)) {
                    showLastQspError();
                }
            });
            counterHandler.postDelayed(this, timerInterval);
        }
    };

    private volatile boolean libQspThreadRunning;
    private volatile Handler libQspHandler;
    private volatile long gameStartTime;
    private volatile long lastMsCountCallTime;
    private volatile int timerInterval;
    private PlayerView playerView;

    public LibQspProxyImpl(Context context, ImageProvider imageProvider) {
        this.context = context;
        this.imageProvider = imageProvider;

        settings = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public void close() {
        audioPlayer.destroy();
        stopQspThread();
    }

    private void stopQspThread() {
        if (!libQspThreadRunning) {
            return;
        }
        Handler handler = libQspHandler;
        if (handler != null) {
            handler.getLooper().quitSafely();
        }
        logger.info("QSP library thread stopped");
    }

    private void showLastQspError() {
        JniResult errorResult = (JniResult) QSPGetLastErrorData();

        String locName = errorResult.str1 == null ? "" : errorResult.str1;
        int action = errorResult.int2;
        int line = errorResult.int3;
        int errorNumber = errorResult.int1;

        String desc = QSPGetErrorDesc(errorResult.int1);
        if (desc == null) {
            desc = "";
        }

        final String message = String.format(
                Locale.getDefault(),
                "Location: %s\nAction: %d\nLine: %d\nError number: %d\nDescription: %s",
                locName,
                action,
                line,
                errorNumber,
                desc);

        logger.error(message);

        PlayerView view = playerView;
        if (view != null) {
            playerView.showError(message);
        }
    }

    private void runOnQspThread(final Runnable runnable) {
        if (libQspLock.isLocked()) {
            return;
        }
        if (!libQspThreadRunning) {
            runLibQspThread();
        }
        libQspHandler.post(() -> {
            libQspLock.lock();
            try {
                runnable.run();
            } finally {
                libQspLock.unlock();
            }
        });
    }

    private void runLibQspThread() {
        final CountDownLatch latch = new CountDownLatch(1);

        new Thread("libqsp") {
            @Override
            public void run() {
                libQspThreadRunning = true;
                QSPInit();
                Looper.prepare();
                libQspHandler = new Handler();
                latch.countDown();
                Looper.loop();
                QSPDeInit();
                libQspThreadRunning = false;
            }
        }
                .start();

        try {
            latch.await();
            logger.info("QSP library thread started");
        } catch (InterruptedException e) {
            logger.error("Wait failed", e);
        }
    }

    private boolean loadGameWorld() {
        byte[] gameData;
        try (FileInputStream in = new FileInputStream(viewState.gameFile)) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                StreamUtil.copy(in, out);
                gameData = out.toByteArray();
            }
        } catch (IOException e) {
            logger.error("Failed to load the game world", e);
            return false;
        }
        String fileName = viewState.gameFile.getAbsolutePath();
        if (!QSPLoadGameWorldFromData(gameData, gameData.length, fileName)) {
            showLastQspError();
            return false;
        }

        return true;
    }

    private boolean loadUIConfiguration() {
        boolean changed = false;

        JniResult htmlResult = (JniResult) QSPGetVarValues("USEHTML", 0);
        if (htmlResult.success) {
            boolean useHtml = htmlResult.int1 != 0;
            if (viewState.useHtml != useHtml) {
                viewState.useHtml = useHtml;
                changed = true;
            }
        }

        JniResult fSizeResult = (JniResult) QSPGetVarValues("FSIZE", 0);
        if (fSizeResult.success && viewState.fontSize != fSizeResult.int1) {
            viewState.fontSize = fSizeResult.int1;
            changed = true;
        }

        JniResult bColorResult = (JniResult) QSPGetVarValues("BCOLOR", 0);
        if (bColorResult.success && viewState.backColor != bColorResult.int1) {
            viewState.backColor = bColorResult.int1;
            changed = true;
        }

        JniResult fColorResult = (JniResult) QSPGetVarValues("FCOLOR", 0);
        if (fColorResult.success && viewState.fontColor != fColorResult.int1) {
            viewState.fontColor = fColorResult.int1;
            changed = true;
        }

        JniResult lColorResult = (JniResult) QSPGetVarValues("LCOLOR", 0);
        if (lColorResult.success && viewState.linkColor != lColorResult.int1) {
            viewState.linkColor = lColorResult.int1;
            changed = true;
        }

        return changed;
    }

    private void loadActions() {
        ArrayList<QspListItem> actions = new ArrayList<>();
        int count = QSPGetActionsCount();
        for (int i = 0; i < count; ++i) {
            JniResult actionResult = (JniResult) QSPGetActionData(i);
            QspListItem action = new QspListItem();
            action.icon = imageProvider.getDrawable(FileUtil.normalizePath(actionResult.str2));
            action.text = viewState.useHtml ? HtmlUtil.removeHtmlTags(actionResult.str1) : actionResult.str1;
            actions.add(action);
        }
        viewState.actions = actions;
    }

    private void loadObjects() {
        ArrayList<QspListItem> objects = new ArrayList<>();
        int count = QSPGetObjectsCount();
        for (int i = 0; i < count; i++) {
            JniResult objectResult = (JniResult) QSPGetObjectData(i);
            QspListItem object = new QspListItem();
            object.icon = imageProvider.getDrawable(FileUtil.normalizePath(objectResult.str2));
            object.text = viewState.useHtml ? HtmlUtil.removeHtmlTags(objectResult.str1) : objectResult.str1;
            objects.add(object);
        }
        viewState.objects = objects;
    }

    // Begin LibQspProxy implementation

    @Override
    public PlayerViewState getViewState() {
        return viewState;
    }

    @Override
    public void setPlayerView(PlayerView view) {
        playerView = view;
    }

    @Override
    public void execute(final String code) {
        runOnQspThread(() -> {
            if (!QSPExecString(code, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onActionSelected(final int index) {
        runOnQspThread(() -> {
            if (!QSPSetSelActionIndex(index, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onActionClicked(final int index) {
        runOnQspThread(() -> {
            if (!QSPSetSelActionIndex(index, false)) {
                showLastQspError();
            }
            if (!QSPExecuteSelActionCode(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onObjectSelected(final int index) {
        runOnQspThread(() -> {
            if (!QSPSetSelObjectIndex(index, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onInputAreaClicked() {
        final PlayerView view = playerView;
        if (view == null) {
            return;
        }
        runOnQspThread(() -> {
            String input = view.showInputBox(context.getString(R.string.userInput));
            QSPSetInputStrText(input);

            if (!QSPExecUserInput(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void runGame(final String title, final File dir, final File file) {
        runOnQspThread(() -> doRunGame(title, dir, file));
    }

    private void doRunGame(final String title, final File dir, final File file) {
        counterHandler.removeCallbacks(counterTask);
        audioPlayer.closeAllFiles();

        viewState.reset();
        viewState.gameRunning = true;
        viewState.gameTitle = title;
        viewState.gameDir = dir;
        viewState.gameFile = file;

        imageProvider.setGameDirectory(dir);

        if (!loadGameWorld()) {
            return;
        }

        gameStartTime = SystemClock.elapsedRealtime();
        lastMsCountCallTime = 0;
        timerInterval = 500;

        if (!QSPRestartGame(true)) {
            showLastQspError();
        }

        counterHandler.postDelayed(counterTask, timerInterval);
    }

    @Override
    public void restartGame() {
        runOnQspThread(() -> {
            PlayerViewState state = viewState;
            doRunGame(state.gameTitle, state.gameDir, state.gameFile);
        });
    }

    @Override
    public void resumeGame() {
        audioPlayer.setSoundEnabled(settings.getBoolean("sound", true));
        audioPlayer.resume();
        counterHandler.postDelayed(counterTask, timerInterval);
    }

    @Override
    public void pauseGame() {
        counterHandler.removeCallbacks(counterTask);
        audioPlayer.pause();
    }

    @Override
    public void loadGameState(final Uri uri) {
        if (Thread.currentThread() != libQspHandler.getLooper().getThread()) {
            runOnQspThread(() -> loadGameState(uri));
            return;
        }

        final byte[] gameData;

        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                StreamUtil.copy(in, out);
                gameData = out.toByteArray();
            }
        } catch (IOException e) {
            logger.error("Failed to load game state", e);
            return;
        }

        counterHandler.removeCallbacks(counterTask);

        if (!QSPOpenSavedGameFromData(gameData, gameData.length, true)) {
            showLastQspError();
        }

        counterHandler.postDelayed(counterTask, timerInterval);
    }

    @Override
    public void saveGameState(final Uri uri) {
        if (Thread.currentThread() != libQspHandler.getLooper().getThread()) {
            runOnQspThread(() -> saveGameState(uri));
            return;
        }

        byte[] gameData = QSPSaveGameAsData(false);
        if (gameData == null) {
            return;
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
            out.write(gameData);
        } catch (IOException e) {
            logger.error("Failed to save the game state", e);
        }
    }

    // End LibQspProxy

    // Begin QSP library callbacks

    private void RefreshInt() {
        boolean confChanged = loadUIConfiguration();

        boolean mainDescChanged = QSPIsMainDescChanged();
        if (mainDescChanged) {
            viewState.mainDesc = QSPGetMainDesc();
        }

        boolean actionsChanged = QSPIsActionsChanged();
        if (actionsChanged) {
            loadActions();
        }

        boolean objectsChanged = QSPIsObjectsChanged();
        if (objectsChanged) {
            loadObjects();
        }

        boolean varsDescChanged = QSPIsVarsDescChanged();
        if (varsDescChanged) {
            viewState.varsDesc = QSPGetVarsDesc();
        }

        PlayerView view = playerView;
        if (view != null) {
            playerView.refreshGameView(
                    confChanged,
                    mainDescChanged,
                    actionsChanged,
                    objectsChanged,
                    varsDescChanged);
        }
    }

    private void ShowPicture(String path) {
        PlayerView view = playerView;
        if (view == null || path == null || path.isEmpty()) {
            return;
        }
        view.showPicture(path);
    }

    private void SetTimer(int msecs) {
        timerInterval = msecs;
    }

    private void ShowMessage(String message) {
        PlayerView view = playerView;
        if (view != null) {
            view.showMessage(message);
        }
    }

    private void PlayFile(String path, int volume) {
        if (path != null && !path.isEmpty()) {
            audioPlayer.playFile(FileUtil.normalizePath(path), volume);
        }
    }

    private boolean IsPlayingFile(final String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }

        return audioPlayer.isPlayingFile(FileUtil.normalizePath(path));
    }

    private void CloseFile(String path) {
        if (path == null || path.isEmpty()) {
            audioPlayer.closeAllFiles();
        } else {
            audioPlayer.closeFile(FileUtil.normalizePath(path));
        }
    }

    private void OpenGame(String filename) {
        File savesDir = FileUtil.getOrCreateDirectory(viewState.gameDir, "saves");
        File saveFile = FileUtil.findFileOrDirectory(savesDir, filename);
        if (saveFile == null) {
            logger.error("Save file not found: " + filename);
            return;
        }
        loadGameState(Uri.fromFile(saveFile));
    }

    private void SaveGame(String filename) {
        PlayerView view = playerView;
        if (view != null) {
            view.showSaveGamePopup(filename);
        }
    }

    private String InputBox(String prompt) {
        PlayerView view = playerView;
        return view != null ? view.showInputBox(prompt) : null;
    }

    private int GetMSCount() {
        long now = SystemClock.elapsedRealtime();
        if (lastMsCountCallTime == 0) {
            lastMsCountCallTime = gameStartTime;
        }
        int dt = (int) (now - lastMsCountCallTime);
        lastMsCountCallTime = now;

        return dt;
    }

    private void AddMenuItem(String name, String imgPath) {
        QspMenuItem item = new QspMenuItem();
        item.imgPath = FileUtil.normalizePath(imgPath);
        item.name = name;
        viewState.menuItems.add(item);
    }

    private void ShowMenu() {
        PlayerView view = playerView;
        if (view == null) {
            return;
        }
        int result = view.showMenu();
        if (result != -1) {
            QSPSelectMenuItem(result);
        }
    }

    private void DeleteMenu() {
        viewState.menuItems.clear();
    }

    private void Wait(int msecs) {
        try {
            Thread.sleep(msecs);
        } catch (InterruptedException e) {
            logger.error("Wait failed", e);
        }
    }

    private void ShowWindow(int type, boolean isShow) {
        WindowType windowType = WindowType.values()[type];
        playerView.showWindow(windowType, isShow);
    }

    private byte[] GetFileContents(String path) {
        String normPath = FileUtil.normalizePath(path);
        return FileUtil.getFileContents(normPath);
    }

    private void ChangeQuestPath(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            logger.error("Game directory not found: " + path);
            return;
        }
        if (!viewState.gameDir.equals(dir)) {
            viewState.gameDir = dir;
            imageProvider.setGameDirectory(dir);
        }
    }

    // End QSP library callbacks

    // Begin JNI

    public native void QSPInit();
    public native void QSPDeInit();
    public native boolean QSPIsInCallBack();
    public native void QSPEnableDebugMode(boolean isDebug);
    public native Object QSPGetCurStateData();//!!!STUB
    public native String QSPGetVersion();
    public native int QSPGetFullRefreshCount();
    public native String QSPGetQstFullPath();
    public native String QSPGetCurLoc();
    public native String QSPGetMainDesc();
    public native boolean QSPIsMainDescChanged();
    public native String QSPGetVarsDesc();
    public native boolean QSPIsVarsDescChanged();
    public native Object QSPGetExprValue();//!!!STUB
    public native void QSPSetInputStrText(String val);
    public native int QSPGetActionsCount();
    public native Object QSPGetActionData(int ind);//!!!STUB
    public native boolean QSPExecuteSelActionCode(boolean isRefresh);
    public native boolean QSPSetSelActionIndex(int ind, boolean isRefresh);
    public native int QSPGetSelActionIndex();
    public native boolean QSPIsActionsChanged();
    public native int QSPGetObjectsCount();
    public native Object QSPGetObjectData(int ind);//!!!STUB
    public native boolean QSPSetSelObjectIndex(int ind, boolean isRefresh);
    public native int QSPGetSelObjectIndex();
    public native boolean QSPIsObjectsChanged();
    public native void QSPShowWindow(int type, boolean isShow);
    public native Object QSPGetVarValuesCount(String name);
    public native Object QSPGetVarValues(String name, int ind);//!!!STUB
    public native int QSPGetMaxVarsCount();
    public native Object QSPGetVarNameByIndex(int index);//!!!STUB
    public native boolean QSPExecString(String s, boolean isRefresh);
    public native boolean QSPExecLocationCode(String name, boolean isRefresh);
    public native boolean QSPExecCounter(boolean isRefresh);
    public native boolean QSPExecUserInput(boolean isRefresh);
    public native Object QSPGetLastErrorData();
    public native String QSPGetErrorDesc(int errorNum);
    public native boolean QSPLoadGameWorld(String fileName);
    public native boolean QSPLoadGameWorldFromData(byte data[], int dataSize, String fileName);
    public native boolean QSPSaveGame(String fileName, boolean isRefresh);
    public native byte[] QSPSaveGameAsData(boolean isRefresh);
    public native boolean QSPOpenSavedGame(String fileName, boolean isRefresh);
    public native boolean QSPOpenSavedGameFromData(byte data[], int dataSize, boolean isRefresh);
    public native boolean QSPRestartGame(boolean isRefresh);
    public native void QSPSelectMenuItem(int index);
    //public native void QSPSetCallBack(int type, QSP_CALLBACK func)

    static {
        System.loadLibrary("ndkqsp");
    }

    // End JNI
}