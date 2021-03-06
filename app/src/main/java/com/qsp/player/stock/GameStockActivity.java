package com.qsp.player.stock;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.collection.SparseArrayCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;

import com.qsp.player.R;
import com.qsp.player.Settings;
import com.qsp.player.SettingsActivity;
import com.qsp.player.stock.install.ArchiveGameInstaller;
import com.qsp.player.stock.install.FolderGameInstaller;
import com.qsp.player.stock.install.GameInstaller;
import com.qsp.player.stock.install.InstallException;
import com.qsp.player.stock.install.InstallType;
import com.qsp.player.stock.repository.LocalGameRepository;
import com.qsp.player.stock.repository.RemoteGameRepository;
import com.qsp.player.util.ViewUtil;
import com.qsp.player.util.ZipUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import static com.qsp.player.util.ColorUtil.getHexColor;
import static com.qsp.player.util.FileUtil.GAME_INFO_FILENAME;
import static com.qsp.player.util.FileUtil.createFile;
import static com.qsp.player.util.FileUtil.deleteDirectory;
import static com.qsp.player.util.FileUtil.findFileOrDirectory;
import static com.qsp.player.util.FileUtil.getOrCreateDirectory;
import static com.qsp.player.util.FileUtil.isWritableDirectory;
import static com.qsp.player.util.FileUtil.isWritableFile;
import static com.qsp.player.util.FileUtil.normalizeGameFolderName;
import static com.qsp.player.util.GameDirUtil.doesDirectoryContainGameFiles;
import static com.qsp.player.util.GameDirUtil.normalizeGameDirectory;
import static com.qsp.player.util.ViewUtil.getFontStyle;
import static com.qsp.player.util.ViewUtil.setLocale;

public class GameStockActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_INSTALL_GAME = 1;

    private static final int TAB_LOCAL = 0;
    private static final int TAB_REMOTE = 1;
    private static final int TAB_ALL = 2;

    private static final String ABOUT_TEMPLATE = "<html><head>\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1\">\n" +
            "<style type=\"text/css\">\n" +
            "body{margin: 0; padding: 0; color: QSPTEXTCOLOR; background-color: QSPBACKCOLOR; max-width: 100%; font-size: QSPFONTSIZE; font-family: QSPFONTSTYLE; }\n" +
            "a{color: QSPLINKCOLOR; }\n" +
            "a:link{color: QSPLINKCOLOR; }\n" +
            "table{font-size: QSPFONTSIZE; font-family: QSPFONTSTYLE; }\n" +
            "</style></head><body>REPLACETEXT</body></html>";

    private static final Logger logger = LoggerFactory.getLogger(GameStockActivity.class);

    private final HashMap<String, GameStockItem> gamesMap = new HashMap<>();
    private final SparseArrayCompat<GameStockItemAdapter> gameAdapters = new SparseArrayCompat<>();
    private final LocalGameRepository localGameRepository = new LocalGameRepository();
    private final HashMap<InstallType, GameInstaller> installers = new HashMap<>();

    private Settings settings;
    private String gameRunning;
    private boolean showProgressDialog;
    private String currentLanguage = Locale.getDefault().getLanguage();
    private ListView gamesView;
    private ProgressDialog progressDialog;
    private ConnectivityManager connectivityManager;
    private Collection<GameStockItem> remoteGames;
    private File gamesDir;
    private DownloadGameAsyncTask downloadTask;
    private LoadGameListAsyncTask loadGameListTask;
    private InstallType lastInstallType = InstallType.ARCHIVE;

    public GameStockActivity() {
        installers.put(InstallType.ARCHIVE, new ArchiveGameInstaller(this));
        installers.put(InstallType.FOLDER, new FolderGameInstaller(this));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stock);

        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        Intent intent = getIntent();
        gameRunning = intent.getStringExtra("gameRunning");

        loadSettings();
        loadLocale();
        initGamesListView();
        initActionBar(savedInstanceState);
        setResult(RESULT_CANCELED);

        logger.info("GameStockActivity created");
    }

    private void loadSettings() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        settings = Settings.from(preferences);
    }

    private void loadLocale() {
        setLocale(this, settings.getLanguage());
        setTitle(R.string.gameStock);
        currentLanguage = settings.getLanguage();
    }

    private void initActionBar(Bundle savedInstanceState) {
        TabListener tabListener = new TabListener();
        ActionBar bar = getSupportActionBar();
        if (bar == null) {
            return;
        }
        bar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
        bar.addTab(bar.newTab()
                .setText(R.string.tabLocal)
                .setTabListener(tabListener), false);

        bar.addTab(bar.newTab()
                .setText(R.string.tabRemote)
                .setTabListener(tabListener), false);

        bar.addTab(bar.newTab()
                .setText(R.string.tabAll)
                .setTabListener(tabListener), false);

        int tab;
        if (savedInstanceState != null) {
            tab = savedInstanceState.getInt("tab", TAB_LOCAL);
        } else {
            tab = TAB_LOCAL;
        }
        bar.setSelectedNavigationItem(tab);
    }

    private void initGamesListView() {
        gamesView = findViewById(R.id.games);
        gamesView.setTextFilterEnabled(true);
        gamesView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        gamesView.setOnItemClickListener((parent, view, position, id) -> {
            String gameId = getGameIdByPosition(position);
            showGameInfo(gameId);
        });
        gamesView.setOnItemLongClickListener((parent, view, position, id) -> {
            String gameId = getGameIdByPosition(position);
            GameStockItem game = gamesMap.get(gameId);
            if (game != null) {
                playGame(game);
            } else {
                logger.error("Game not found: " + gameId);
            }
            return true;
        });
    }

    private String getGameIdByPosition(int position) {
        GameStockItem game = (GameStockItem) gamesView.getAdapter().getItem(position);
        return game.getId();
    }

    private void showGameInfo(String gameId) {
        final GameStockItem game = gamesMap.get(gameId);
        if (game == null) {
            logger.error("Game not found: " + gameId);
            return;
        }
        StringBuilder message = new StringBuilder();
        if (game.getAuthor().length() > 0) {
            message.append(getString(R.string.author).replace("-AUTHOR-", game.getAuthor()));
        }
        if (game.getVersion().length() > 0) {
            message.append('\n');
            message.append(getString(R.string.version).replace("-VERSION-", game.getVersion()));
        }
        if (game.getFileSize() > 0) {
            message.append('\n');
            message.append(getString(R.string.fileSize).replace("-SIZE-", Integer.toString(game.getFileSize() / 1024)));
        }
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this)
                .setMessage(message)
                .setTitle(game.getTitle())
                .setIcon(R.drawable.icon)
                .setNegativeButton(getString(R.string.close), (dialog, which) -> dialog.cancel());

        if (game.isInstalled()) {
            alertBuilder.setNeutralButton(getString(R.string.play), (dialog, which) -> playGame(game));
        }
        if (game.hasRemoteUrl()) {
            alertBuilder.setPositiveButton(game.isInstalled() ? getString(R.string.update) : getString(R.string.download), (dialog, which) -> downloadGame(game));
        }
        alertBuilder.create().show();
    }

    private void playGame(final GameStockItem game) {
        final Intent data = new Intent();
        data.putExtra("gameId", game.getId());
        data.putExtra("gameTitle", game.getTitle());
        data.putExtra("gameDirUri", game.getGameDir().getAbsolutePath());

        int gameFileCount = game.getGameFiles().size();
        switch (gameFileCount) {
            case 0:
                logger.warn("Game has no game files");
                return;
            case 1:
                data.putExtra("gameFileUri", game.getGameFiles().get(0).getAbsolutePath());
                setResult(RESULT_OK, data);
                finish();
                return;
            default:
                break;
        }

        ArrayList<String> names = new ArrayList<>();
        for (File file : game.getGameFiles()) {
            names.add(file.getName());
        }
        new AlertDialog.Builder(GameStockActivity.this)
                .setTitle(getString(R.string.selectGameFile))
                .setCancelable(false)
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    data.putExtra("gameFileUri", game.getGameFiles().get(which).getAbsolutePath());
                    setResult(RESULT_OK, data);
                    finish();
                })
                .show();
    }

    private void downloadGame(GameStockItem game) {
        if (!isNetworkConnected()) {
            ViewUtil.showErrorDialog(this, getString(R.string.downloadNetworkError));
            return;
        }
        DownloadGameAsyncTask task = new DownloadGameAsyncTask(this, game);
        task.execute();
        downloadTask = task;
    }

    private boolean isNetworkConnected() {
        NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    @Override
    protected void onDestroy() {
        if (downloadTask != null) {
            downloadTask.cancel(true);
        }
        if (loadGameListTask != null) {
            loadGameListTask.cancel(true);
        }
        super.onDestroy();
        logger.info("GameStockActivity destroyed");
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            outState.putInt("tab", bar.getSelectedNavigationIndex());
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        loadSettings();
        updateLocale();
        refreshGamesDirectory();

        if (showProgressDialog && progressDialog != null) {
            progressDialog.show();
        }
    }

    private void updateLocale() {
        if (currentLanguage.equals(settings.getLanguage())) return;

        setLocale(this, settings.getLanguage());
        setTitle(getString(R.string.gameStock));
        refreshActionBar();
        invalidateOptionsMenu();

        currentLanguage = settings.getLanguage();
    }

    private void refreshActionBar() {
        ActionBar bar = getSupportActionBar();
        if (bar == null) return;

        bar.getTabAt(2).setText(getString(R.string.tabAll));
        bar.getTabAt(1).setText(getString(R.string.tabRemote));
        bar.getTabAt(0).setText(getString(R.string.tabLocal));
    }

    private void refreshGamesDirectory() {
        File extFilesDir = getExternalFilesDir(null);
        if (extFilesDir == null) {
            logger.error("External files directory not found");
            return;
        }
        File dir = getOrCreateDirectory(extFilesDir, "games");
        if (!isWritableDirectory(dir)) {
            logger.error("Games directory is not writable");
            String message = getString(R.string.gamesDirError);
            ViewUtil.showErrorDialog(this, message);
            return;
        }
        gamesDir = dir;
        localGameRepository.setGamesDirectory(gamesDir);
        refreshGames();
    }

    private void refreshGames() {
        gamesMap.clear();

        if (remoteGames != null) {
            for (GameStockItem game : remoteGames) {
                gamesMap.put(game.getId(), game);
            }
        }
        for (GameStockItem localGame : localGameRepository.getGames()) {
            GameStockItem remoteGame = gamesMap.get(localGame.getId());
            if (remoteGame != null) {
                GameStockItem aggregateGame = new GameStockItem(remoteGame);
                aggregateGame.setGameDir(localGame.getGameDir());
                aggregateGame.setGameFiles(localGame.getGameFiles());
                gamesMap.put(localGame.getId(), aggregateGame);
            } else {
                gamesMap.put(localGame.getId(), localGame);
            }
        }

        refreshGameAdapters();
    }

    private void refreshGameAdapters() {
        ArrayList<GameStockItem> games = getSortedGames();
        ArrayList<GameStockItem> localGames = new ArrayList<>();
        ArrayList<GameStockItem> remoteGames = new ArrayList<>();

        for (GameStockItem game : games) {
            if (game.isInstalled()) {
                localGames.add(game);
            }
            if (game.hasRemoteUrl()) {
                remoteGames.add(game);
            }
        }

        gameAdapters.put(TAB_LOCAL, new GameStockItemAdapter(this, R.layout.list_item_game, localGames));
        gameAdapters.put(TAB_REMOTE, new GameStockItemAdapter(this, R.layout.list_item_game, remoteGames));
        gameAdapters.put(TAB_ALL, new GameStockItemAdapter(this, R.layout.list_item_game, games));

        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            setGameAdapterFromTab(bar.getSelectedNavigationIndex());
        }
    }

    private ArrayList<GameStockItem> getSortedGames() {
        Collection<GameStockItem> unsortedGames = gamesMap.values();
        ArrayList<GameStockItem> games = new ArrayList<>(unsortedGames);

        if (games.size() < 2) {
            return games;
        }
        Collections.sort(games, (first, second) -> first.getTitle().toLowerCase()
                .compareTo(second.getTitle().toLowerCase()));

        return games;
    }

    @Override
    public void onPause() {
        if (progressDialog != null) {
            progressDialog.hide();
        }
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_CODE_INSTALL_GAME) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK) return;

        Uri uri;
        if (data == null || (uri = data.getData()) == null) {
            logger.error("Game archive is not selected");
            return;
        }
        installGame(uri, lastInstallType);
    }

    private void installGame(Uri uri, InstallType type) {
        if (!isWritableDirectory(gamesDir)) {
            logger.error("Games directory is not writable");
            return;
        }
        GameInstaller installer = installers.get(type);
        if (installer == null) {
            logger.error(String.format("Installer not found by install type '%s'", type));
            return;
        }
        try {
            doInstallGame(installer, uri);
        } catch (InstallException ex) {
            logger.error(ex.getMessage());
        }
    }

    private void doInstallGame(GameInstaller installer, Uri uri) {
        installer.load(uri);

        File gameDir = getOrCreateGameDirectory(installer.getGameName());
        if (!isWritableDirectory(gameDir)) {
            logger.error("Game directory is not writable");
            return;
        }
        updateProgressDialog(true, installer.getGameName(), getString(R.string.installing), null);

        boolean installed = installer.install(gameDir);
        if (installed) {
            refreshGames();
        }

        updateProgressDialog(false, "", "", null);
    }

    private File getOrCreateGameDirectory(String gameName) {
        String folderName = normalizeGameFolderName(gameName);
        return getOrCreateDirectory(gamesDir, folderName);
    }

    private boolean unzip(File zipFile, File dir) {
        if (!isWritableDirectory(dir)) {
            logger.error("Game directory is not writable");
            return false;
        }
        return ZipUtil.unzip(this, DocumentFile.fromFile(zipFile), dir);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_stock, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean isLollipopOrGreater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;

        MenuItem installFromFolderItem = menu.findItem(R.id.menu_installfromfolder);
        installFromFolderItem.setEnabled(isLollipopOrGreater);

        MenuItem resumeGameItem = menu.findItem(R.id.menu_resumegame);
        resumeGameItem.setVisible(gameRunning != null);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_resumegame:
                setResult(RESULT_CANCELED, null);
                finish();
                return true;

            case R.id.menu_options:
                showSettings();
                return true;

            case R.id.menu_about:
                showAboutDialog();
                return true;

            case R.id.menu_installfromarchive:
                showInstallGameDialog(InstallType.ARCHIVE);
                return true;

            case R.id.menu_installfromfolder:
                showInstallGameDialog(InstallType.FOLDER);
                return true;

            case R.id.menu_deletegame:
                showDeleteGameDialog();
                return true;
        }

        return false;
    }

    private void showInstallGameDialog(InstallType installType) {
        boolean installFromArchive = installType == InstallType.ARCHIVE;
        String action = installFromArchive ?
                Intent.ACTION_OPEN_DOCUMENT :
                Intent.ACTION_OPEN_DOCUMENT_TREE;

        lastInstallType = installType;
        Intent intent = new Intent(action);
        if (installFromArchive) {
            intent.setType("application/zip");
        }
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_INSTALL_GAME);
    }

    private void showSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void showAboutDialog() {
        View messageView = getLayoutInflater().inflate(R.layout.dialog_about, null, false);

        String desc = ABOUT_TEMPLATE
                .replace("QSPFONTSTYLE", getFontStyle(settings.getTypeface()))
                .replace("QSPFONTSIZE", Integer.toString(settings.getFontSize()))
                .replace("QSPTEXTCOLOR", getHexColor(settings.getTextColor()))
                .replace("QSPBACKCOLOR", getHexColor(settings.getBackColor()))
                .replace("QSPLINKCOLOR", getHexColor(settings.getLinkColor()))
                .replace("REPLACETEXT", getString(R.string.appDescription) + getString(R.string.appCredits));

        WebView descView = messageView.findViewById(R.id.about_descrip);
        descView.loadDataWithBaseURL("", desc, "text/html", "utf-8", "");

        new AlertDialog.Builder(this)
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                })
                .setView(messageView)
                .create()
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (downloadTask != null &&
                downloadTask.getStatus() == AsyncTask.Status.RUNNING &&
                keyCode == KeyEvent.KEYCODE_BACK &&
                event.getRepeatCount() == 0) {

            moveTaskToBack(true);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private void updateProgressDialog(boolean show, String title, String message, final Runnable onCancel) {
        showProgressDialog = show;

        if (show) {
            if (progressDialog == null) {
                progressDialog = new ProgressDialog(this);
                progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            }
            progressDialog.setTitle(title);
            progressDialog.setMessage(message);
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            if (onCancel != null) {
                progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.cancel), (dialog, which) -> {
                    if (which == DialogInterface.BUTTON_NEGATIVE) {
                        dialog.dismiss();
                        onCancel.run();
                    }
                });
            }
            if (!progressDialog.isShowing()) {
                progressDialog.show();
            }
        } else if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private void showDeleteGameDialog() {
        ArrayList<GameStockItem> deletableGames = new ArrayList<>();
        ArrayList<String> items = new ArrayList<>();

        for (GameStockItem game : gamesMap.values()) {
            if (!game.isInstalled()) continue;

            deletableGames.add(game);
            items.add(game.getTitle());
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.deleteGameCmd))
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    GameStockItem game = deletableGames.get(which);
                    showConfirmDeleteDialog(game);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                })
                .create()
                .show();
    }

    private void showConfirmDeleteDialog(final GameStockItem game) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.deleteGameQuery).replace("-GAMENAME-", "\"" + game.getTitle() + "\""))
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        if (gameRunning != null && gameRunning.equals(game.getId())) {
                            gameRunning = null;
                            invalidateOptionsMenu();
                        }
                        deleteDirectory(game.getGameDir());
                        ViewUtil.showToast(this, getString(R.string.gameDeleted));
                        refreshGames();
                    }
                })
                .setNegativeButton(android.R.string.no, (dialog, whichButton) -> {
                })
                .create()
                .show();
    }

    private void setRemoteGames(List<GameStockItem> games) {
        remoteGames = games;
        refreshGames();
    }

    private void setGameAdapterFromTab(int tab) {
        switch (tab) {
            case TAB_LOCAL:
            case TAB_REMOTE:
            case TAB_ALL:
                gamesView.setAdapter(gameAdapters.get(tab));
                break;
        }
    }

    private class GameStockItemAdapter extends ArrayAdapter<GameStockItem> {
        private final ArrayList<GameStockItem> items;

        GameStockItemAdapter(Context context, int resource, ArrayList<GameStockItem> items) {
            super(context, resource, items);
            this.items = items;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater inflater = getLayoutInflater();
                convertView = inflater.inflate(R.layout.list_item_game, null);
            }
            GameStockItem item = items.get(position);
            if (item != null) {
                TextView titleView = convertView.findViewById(R.id.game_title);
                if (titleView != null) {
                    titleView.setText(item.getTitle());
                    if (item.isInstalled()) {
                        titleView.setTextColor(0xFFE0E0E0);
                    } else {
                        titleView.setTextColor(0xFFFFD700);
                    }
                }
                TextView authorView = convertView.findViewById(R.id.game_author);
                if (item.getAuthor().length() > 0) {
                    String text = getString(R.string.author).replace("-AUTHOR-", item.getAuthor());
                    authorView.setText(text);
                } else {
                    authorView.setText("");
                }
            }

            return convertView;
        }
    }

    private class TabListener implements ActionBar.TabListener {
        @Override
        public void onTabSelected(ActionBar.Tab tab, FragmentTransaction ft) {
            int position = tab.getPosition();
            boolean tabHasRemoteGames = position == TAB_REMOTE || position == TAB_ALL;
            boolean gamesNotBeingLoaded = loadGameListTask == null || loadGameListTask.getStatus() == AsyncTask.Status.FINISHED;

            if (tabHasRemoteGames && gamesNotBeingLoaded) {
                if (isNetworkConnected()) {
                    LoadGameListAsyncTask task = new LoadGameListAsyncTask(GameStockActivity.this);
                    loadGameListTask = task;
                    task.execute();
                } else {
                    ViewUtil.showErrorDialog(GameStockActivity.this, getString(R.string.loadGameListNetworkError));
                }
            }
            setGameAdapterFromTab(position);
        }

        @Override
        public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction ft) {
        }

        @Override
        public void onTabReselected(ActionBar.Tab tab, FragmentTransaction ft) {
        }
    }

    private static class LoadGameListAsyncTask extends AsyncTask<Void, Void, List<GameStockItem>> {
        private final WeakReference<GameStockActivity> activity;
        private final RemoteGameRepository remoteGameRepository = new RemoteGameRepository();

        private LoadGameListAsyncTask(GameStockActivity activity) {
            this.activity = new WeakReference<>(activity);
        }

        @Override
        protected void onPreExecute() {
            GameStockActivity activity = this.activity.get();
            if (activity != null) {
                activity.updateProgressDialog(true, "", activity.getString(R.string.gameListLoading), null);
            }
        }

        @Override
        protected List<GameStockItem> doInBackground(Void... params) {
            return remoteGameRepository.getGames();
        }

        @Override
        protected void onPostExecute(List<GameStockItem> result) {
            GameStockActivity activity = this.activity.get();
            if (activity == null) {
                return;
            }
            activity.updateProgressDialog(false, "", "", null);

            if (result == null) {
                String message = activity.getString(R.string.loadGameListError);
                ViewUtil.showErrorDialog(activity, message);
                return;
            }
            activity.setRemoteGames(result);
        }
    }

    private static class DownloadGameAsyncTask extends AsyncTask<Void, DownloadGameAsyncTask.DownloadPhase, DownloadGameAsyncTask.DownloadResult> {
        private final WeakReference<GameStockActivity> activity;
        private final GameStockItem game;

        private volatile boolean cancelled = false;

        private DownloadGameAsyncTask(GameStockActivity activity, GameStockItem game) {
            this.activity = new WeakReference<>(activity);
            this.game = game;
        }

        @Override
        protected void onPreExecute() {
            GameStockActivity activity = this.activity.get();
            if (activity != null) {
                activity.updateProgressDialog(true, game.getTitle(), activity.getString(R.string.downloading), () -> cancelled = true);
            }
        }

        @Override
        protected DownloadResult doInBackground(Void... params) {
            GameStockActivity activity = this.activity.get();
            if (activity == null) {
                return DownloadResult.DOWNLOAD_FAILED;
            }

            File cacheDir = activity.getCacheDir();
            if (!isWritableDirectory(cacheDir)) {
                logger.error("Cache directory is not writable");
                return DownloadResult.DOWNLOAD_FAILED;
            }

            String zipFilename = String.valueOf(SystemClock.elapsedRealtime()).concat("_game");
            File zipFile = createFile(cacheDir, zipFilename);
            if (zipFile == null) {
                logger.error("Failed to create a ZIP file: " + zipFilename);
                return DownloadResult.DOWNLOAD_FAILED;
            }

            boolean downloaded = download(zipFile);
            File gameDir = null;
            boolean extracted = false;

            if (downloaded) {
                publishProgress(DownloadPhase.EXTRACT);
                gameDir = activity.getOrCreateGameDirectory(game.getTitle());
                extracted = activity.unzip(zipFile, gameDir);
            }
            if (zipFile.exists()) {
                zipFile.delete();
            }
            if (!downloaded) {
                return cancelled ? DownloadResult.CANCELLED : DownloadResult.DOWNLOAD_FAILED;
            }
            if (!extracted) {
                return DownloadResult.EXTRACT_FAILED;
            }
            normalizeGameDirectory(gameDir);

            if (!doesDirectoryContainGameFiles(gameDir)) {
                return DownloadResult.GAME_FILES_NOT_FOUND;
            }
            writeGameInfo();

            return DownloadResult.OK;
        }

        private boolean download(File zipFile) {
            GameStockActivity activity = this.activity.get();
            if (activity == null) {
                return false;
            }
            try {
                URL url = new URL(game.getFileUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setDoOutput(true);
                conn.connect();

                try (InputStream in = conn.getInputStream()) {
                    try (FileOutputStream out = new FileOutputStream(zipFile)) {
                        byte[] b = new byte[8192];
                        int totalBytesRead = 0;
                        int bytesRead;
                        while ((bytesRead = in.read(b)) > 0) {
                            if (cancelled) {
                                logger.info("Game download was cancelled");
                                return false;
                            }
                            out.write(b, 0, bytesRead);
                            totalBytesRead += bytesRead;
                        }
                        return totalBytesRead == game.getFileSize();
                    }
                }
            } catch (IOException ex) {
                logger.error("Failed to download a ZIP file", ex);
                return false;
            }
        }

        private boolean writeGameInfo() {
            GameStockActivity activity = this.activity.get();
            if (activity == null) {
                return false;
            }
            String folderName = normalizeGameFolderName(game.getTitle());
            File gameDir = findFileOrDirectory(activity.gamesDir, folderName);
            if (!isWritableDirectory(gameDir)) {
                logger.error("Game directory is not writable");
                return false;
            }
            File infoFile = findFileOrDirectory(gameDir, GAME_INFO_FILENAME);
            if (infoFile == null) {
                infoFile = createFile(gameDir, GAME_INFO_FILENAME);
            }
            if (!isWritableFile(infoFile)) {
                logger.error("Game info file is not writable");
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(infoFile)) {
                try (OutputStreamWriter writer = new OutputStreamWriter(out)) {
                    writer.write("<game>\n");
                    writer.write("\t<id><![CDATA[".concat(game.getId().substring(3)).concat("]]></id>\n"));
                    writer.write("\t<list_id><![CDATA[".concat(game.getListId()).concat("]]></list_id>\n"));
                    writer.write("\t<author><![CDATA[".concat(game.getAuthor()).concat("]]></author>\n"));
                    writer.write("\t<ported_by><![CDATA[".concat(game.getPortedBy()).concat("]]></ported_by>\n"));
                    writer.write("\t<version><![CDATA[".concat(game.getVersion()).concat("]]></version>\n"));
                    writer.write("\t<title><![CDATA[".concat(game.getTitle()).concat("]]></title>\n"));
                    writer.write("\t<lang><![CDATA[".concat(game.getLang()).concat("]]></lang>\n"));
                    writer.write("\t<player><![CDATA[".concat(game.getPlayer()).concat("]]></player>\n"));
                    writer.write("\t<file_url><![CDATA[".concat(game.getFileUrl()).concat("]]></file_url>\n"));
                    writer.write("\t<file_size><![CDATA[".concat(String.valueOf(game.getFileSize())).concat("]]></file_size>\n"));
                    writer.write("\t<desc_url><![CDATA[".concat(game.getDescUrl()).concat("]]></desc_url>\n"));
                    writer.write("\t<pub_date><![CDATA[".concat(game.getPubDate()).concat("]]></pub_date>\n"));
                    writer.write("\t<mod_date><![CDATA[".concat(game.getModDate()).concat("]]></mod_date>\n"));
                    writer.write("</game>");
                }

                return true;
            } catch (IOException ex) {
                logger.error("Failed to write to a game info file", ex);
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(DownloadPhase... values) {
            super.onProgressUpdate(values);
            GameStockActivity activity = this.activity.get();
            if (activity == null) {
                return;
            }
            activity.updateProgressDialog(true, game.getTitle(), activity.getString(R.string.installing), null);
        }

        @Override
        protected void onPostExecute(DownloadResult result) {
            GameStockActivity activity = this.activity.get();
            if (activity == null) {
                return;
            }
            activity.updateProgressDialog(false, "", "", null);

            String message;
            switch (result) {
                case OK:
                    activity.refreshGames();
                    activity.showGameInfo(game.getId());
                    break;
                case DOWNLOAD_FAILED:
                    message = activity.getString(R.string.downloadError).replace("-GAMENAME-", game.getTitle());
                    ViewUtil.showErrorDialog(activity, message);
                    break;
                case EXTRACT_FAILED:
                    message = activity.getString(R.string.extractError).replace("-GAMENAME-", game.getTitle());
                    ViewUtil.showErrorDialog(activity, message);
                    break;
                case GAME_FILES_NOT_FOUND:
                    message = activity.getString(R.string.noGameFilesError);
                    ViewUtil.showErrorDialog(activity, message);
                    break;
            }
        }

        private enum DownloadPhase {
            DOWNLOAD,
            EXTRACT
        }

        private enum DownloadResult {
            OK,
            CANCELLED,
            DOWNLOAD_FAILED,
            EXTRACT_FAILED,
            GAME_FILES_NOT_FOUND,
        }
    }
}
