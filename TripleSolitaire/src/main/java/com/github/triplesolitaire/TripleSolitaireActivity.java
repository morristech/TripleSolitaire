package com.github.triplesolitaire;

import android.annotation.TargetApi;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.AsyncQueryHandler;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ViewFlipper;

import com.github.triplesolitaire.provider.GameContract;
import com.google.android.gms.appstate.AppStateManager;
import com.google.android.gms.appstate.AppStateStatusCodes;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.games.Games;
import com.google.android.gms.games.GamesStatusCodes;
import com.google.android.gms.games.achievement.Achievement;
import com.google.android.gms.games.achievement.AchievementBuffer;
import com.google.android.gms.games.achievement.Achievements;
import com.google.android.gms.plus.PlusShare;
import com.google.example.games.basegameutils.BaseGameActivity;

import java.util.ArrayList;

/**
 * Main class which controls the UI of the Triple Solitaire game
 */
public class TripleSolitaireActivity extends BaseGameActivity implements LoaderCallbacks<Cursor>,
        ResultCallback<AppStateManager.StateResult> {
    private static final int OUR_STATE_KEY = 0;
    private static final int REQUEST_ACHIEVEMENTS = 0;
    private static final int REQUEST_LEADERBOARDS = 1;
    private static final int REQUEST_GAME = 2;
    /**
     * Logging tag
     */
    private static final String TAG = "TripleSolitaireActivity";
    private ViewFlipper googlePlayGamesViewFlipper;
    private boolean mAlreadyLoadedState = false;
    private boolean mPendingUpdateState = false;
    private StatsState stats = new StatsState();
    private AsyncQueryHandler mAsyncQueryHandler;
    private AsyncTask<Void, Void, Void> mPersistStatsAsyncTask = new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(final Void... params) {
            final ArrayList<ContentProviderOperation> operations = stats.getLocalSaveOperations();
            if (BuildConfig.DEBUG)
                Log.d(TripleSolitaireActivity.TAG, "Persisting stats: " + operations.size() + " operations");
            try {
                getContentResolver().applyBatch(GameContract.AUTHORITY, operations);
            } catch (final RemoteException e) {
                Log.e(StatsState.class.getSimpleName(), "Failed persisting stats", e);
            } catch (final OperationApplicationException e) {
                Log.e(StatsState.class.getSimpleName(), "Failed persisting stats", e);
            }
            if (BuildConfig.DEBUG)
                Log.d(TripleSolitaireActivity.TAG, "Persisting stats completed");
            return null;
        }

        @Override
        protected void onPostExecute(final Void result) {
            if (mPendingUpdateState && isSignedIn())
                saveToCloud();
        }
    };

    /**
     * Constructs a new TripleSolitaireActivity that uses all (Games, Plus, AppState) Google Play Services
     */
    public TripleSolitaireActivity() {
        // request that superclass initialize and manage the Google Play Services for us
        super(BaseGameActivity.CLIENT_ALL);
        if (BuildConfig.DEBUG) {
            enableDebugLog(BuildConfig.DEBUG);
        }
    }

    public void newGame() {
        startActivityForResult(new Intent(this, GameActivity.class), REQUEST_GAME);
    }

    private synchronized void incrementAchievements(final AchievementBuffer buffer) {
        final String win10 = getString(R.string.achievement_getting_good);
        final String win100 = getString(R.string.achievement_so_youve_played_triple_solitaire);
        final String win250 = getString(R.string.achievement_stop_playing_never);
        for (final Achievement achievement : buffer) {
            final String achievementId = achievement.getAchievementId();
            if (achievement.getType() != Achievement.TYPE_INCREMENTAL)
                continue;
            final int increment = stats.getGamesWon() - achievement.getCurrentSteps();
            if (achievementId.equals(win10) && increment > 0)
                Games.Achievements.increment(getApiClient(), win10, increment);
            if (achievementId.equals(win100) && increment > 0)
                Games.Achievements.increment(getApiClient(), win100, increment);
            if (achievementId.equals(win250) && increment > 0)
                Games.Achievements.increment(getApiClient(), win250, increment);
        }
    }

    public void onAchievementsLoaded(final Achievements.LoadAchievementsResult loadAchievementsResult) {
        final int statusCode = loadAchievementsResult.getStatus().getStatusCode();
        final AchievementBuffer buffer = loadAchievementsResult.getAchievements();
        switch (statusCode) {
            case GamesStatusCodes.STATUS_OK:
                if (BuildConfig.DEBUG)
                    Log.d(TripleSolitaireActivity.TAG, "Achievements loaded successfully");
                // Data was successfully loaded from the cloud, update incremental achievements
                incrementAchievements(buffer);
                break;
            case GamesStatusCodes.STATUS_NETWORK_ERROR_NO_DATA:
                if (BuildConfig.DEBUG)
                    Log.w(TripleSolitaireActivity.TAG, "Achievements not loaded - network error with no data");
                // can't reach cloud, and we have no local state.
                // TODO warn about network error
                break;
            case GamesStatusCodes.STATUS_NETWORK_ERROR_STALE_DATA:
                if (BuildConfig.DEBUG)
                    Log.w(TripleSolitaireActivity.TAG, "Achievements not loaded - network error with stale data");
                // TODO warn about stale data
                break;
            case GamesStatusCodes.STATUS_CLIENT_RECONNECT_REQUIRED:
                if (BuildConfig.DEBUG)
                    Log.w(TripleSolitaireActivity.TAG, "Achievements not loaded - reconnect required");
                // Need to reconnect GamesClient
                reconnectClient();
                break;
            default:
                // TODO warn about generic error
                break;
        }
        if (buffer != null)
            buffer.close();
    }

    @Override
    protected void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);
        if (request == REQUEST_GAME && response == RESULT_OK) {
            WinDialogFragment winDialogFragment = WinDialogFragment.createInstance(data);
            winDialogFragment.show(getFragmentManager(), "win");
        }
    }

    /**
     * Called when the activity is first created. Sets up the appropriate listeners and starts a new game
     *
     * @see android.app.Activity#onCreate(android.os.Bundle)
     */
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        setContentView(R.layout.main);
        View title = findViewById(R.id.title);
        // The GoogleApiClient is created in super.onCreate so any builder changes must be done beforehand
        getGameHelper().createApiClientBuilder().setViewForPopups(title);
        super.onCreate(savedInstanceState);
        mAsyncQueryHandler = new AsyncQueryHandler(getContentResolver()) {
        };
        googlePlayGamesViewFlipper = (ViewFlipper) findViewById(R.id.google_play_games);
        final Button newGameBtn = (Button) findViewById(R.id.new_game);
        newGameBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                newGame();
            }
        });
        final Button statsBtn = (Button) findViewById(R.id.stats);
        statsBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                StatsDialogFragment.createInstance(stats).show(getFragmentManager(), "stats");
            }
        });
        // Set up sign in button
        final SignInButton signInBtn = (SignInButton) findViewById(R.id.sign_in);
        signInBtn.setOnClickListener(new OnClickListener() {
            @SuppressWarnings("synthetic-access")
            @Override
            public void onClick(final View v) {
                beginUserInitiatedSignIn();
            }
        });
        // Set up Google Play Games buttons
        final Button achievementsBtn = (Button) findViewById(R.id.achievements);
        achievementsBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                Intent achievementsIntent = Games.Achievements.getAchievementsIntent(getApiClient());
                startActivityForResult(achievementsIntent, REQUEST_ACHIEVEMENTS);
            }
        });
        final Button leaderboardsBtn = (Button) findViewById(R.id.leaderboards);
        leaderboardsBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                Intent allLeaderboardsIntent = Games.Leaderboards.getAllLeaderboardsIntent(getApiClient());
                startActivityForResult(allLeaderboardsIntent, REQUEST_LEADERBOARDS);
            }
        });
        final Button shareBtn = (Button) findViewById(R.id.share);
        shareBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                PlusShare.Builder builder = new PlusShare.Builder(TripleSolitaireActivity.this);
                final Uri desktopUrl = Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName());
                final String deepLink = "/share/";
                builder.setContentUrl(desktopUrl).setContentDeepLinkId(deepLink)
                        .addCallToAction("PLAY", desktopUrl, deepLink);
                if (stats.getGamesWon() == 0) {
                    builder.setText(getString(R.string.share_text_no_wins));
                } else {
                    final double bestTime = stats.getShortestTime(false);
                    final int minutes = (int) (bestTime / 60);
                    final int seconds = (int) (bestTime % 60);
                    final StringBuilder sb = new StringBuilder();
                    sb.append(minutes);
                    sb.append(':');
                    if (seconds < 10)
                        sb.append(0);
                    sb.append(seconds);
                    builder.setText(getString(R.string.share_text, sb));
                }
                startActivityForResult(builder.getIntent(), 0);
            }
        });
        getLoaderManager().initLoader(0, null, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
        return new CursorLoader(this, GameContract.Games.CONTENT_URI, null, null, null, null);
    }

    /**
     * One time method to inflate the options menu / action bar
     *
     * @see android.app.Activity#onCreateOptionsMenu(android.view.Menu)
     */
    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public void onLoaderReset(final Loader<Cursor> loader) {
        // Nothing to do
    }

    @Override
    public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
        final int rowCount = data == null ? 0 : data.getCount();
        if (BuildConfig.DEBUG)
            Log.d(TAG, "onLoadFinished found " + rowCount + " rows");
        stats = stats.unionWith(new StatsState(data));
        if (isSignedIn())
            saveToCloud();
        else if (rowCount > 0)
            mPendingUpdateState = true;
    }

    /**
     * Called to handle when options menu / action bar buttons are tapped
     *
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     */
    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                startActivity(new Intent(this, Preferences.class));
                return true;
            case R.id.sign_out:
                signOut();
                googlePlayGamesViewFlipper.setDisplayedChild(1);
                return true;
            case R.id.about:
                final AboutDialogFragment aboutDialogFragment = new AboutDialogFragment();
                aboutDialogFragment.show(getFragmentManager(), "about");
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Method called every time the options menu is invalidated/repainted. Shows/hides the sign out button
     *
     * @see android.app.Activity#onPrepareOptionsMenu(android.view.Menu)
     */
    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(R.id.sign_out).setVisible(isSignedIn());
        return true;
    }

    @Override
    public void onSignInFailed() {
        if (BuildConfig.DEBUG)
            Log.d(TripleSolitaireActivity.TAG, "onSignInFailed");
        invalidateOptionsMenu();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && isAccountAccessRestricted()) {
            googlePlayGamesViewFlipper.setDisplayedChild(0);
        } else {
            googlePlayGamesViewFlipper.setDisplayedChild(1);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private boolean isAccountAccessRestricted() {
        UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
        Bundle restrictions = um.getUserRestrictions();
        return restrictions.getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, false);
    }

    @Override
    public void onSignInSucceeded() {
        if (BuildConfig.DEBUG)
            Log.d(TripleSolitaireActivity.TAG, "onSignInSucceeded");
        invalidateOptionsMenu();
        googlePlayGamesViewFlipper.setDisplayedChild(2);
        if (!mAlreadyLoadedState)
            AppStateManager.load(getApiClient(), OUR_STATE_KEY).setResultCallback(this);
        else if (mPendingUpdateState)
            saveToCloud();
    }

    @Override
    public void onResult(final AppStateManager.StateResult stateResult) {
        final AppStateManager.StateLoadedResult loadedResult = stateResult.getLoadedResult();
        if (loadedResult != null) {
            onStateLoaded(loadedResult);
        }
        final AppStateManager.StateConflictResult conflictResult = stateResult.getConflictResult();
        if (conflictResult != null) {
            onStateConflict(conflictResult);
        }
    }

    private void onStateConflict(final AppStateManager.StateConflictResult conflictResult) {
        final String resolvedVersion = conflictResult.getResolvedVersion();
        final byte[] localData = conflictResult.getLocalData();
        final byte[] serverData = conflictResult.getServerData();
        if (BuildConfig.DEBUG)
            Log.d(TripleSolitaireActivity.TAG, "onStateConflict");
        // Union the two sets of data together to form a resolved, consistent set of stats
        final StatsState localStats = new StatsState(localData);
        final StatsState serverStats = new StatsState(serverData);
        final StatsState resolvedGame = localStats.unionWith(serverStats);
        AppStateManager.resolve(getApiClient(), OUR_STATE_KEY, resolvedVersion, resolvedGame.toBytes())
                .setResultCallback(this);
    }

    private void onStateLoaded(final AppStateManager.StateLoadedResult loadedResult) {
        final int statusCode = loadedResult.getStatus().getStatusCode();
        final byte[] localData = loadedResult.getLocalData();
        switch (statusCode) {
            case AppStateStatusCodes.STATUS_OK:
                if (BuildConfig.DEBUG)
                    Log.d(TripleSolitaireActivity.TAG, "State loaded successfully");
                // Data was successfully loaded from the cloud: merge with local data.
                stats = stats.unionWith(new StatsState(localData));
                mAlreadyLoadedState = true;
                mPersistStatsAsyncTask.execute();
                break;
            case AppStateStatusCodes.STATUS_STATE_KEY_NOT_FOUND:
                if (BuildConfig.DEBUG)
                    Log.d(TripleSolitaireActivity.TAG, "State loaded, no key found");
                // key not found means there is no saved data. To us, this is the same as
                // having empty data, so we treat this as a success.
                mAlreadyLoadedState = true;
                if (mPendingUpdateState)
                    saveToCloud();
                break;
            case AppStateStatusCodes.STATUS_NETWORK_ERROR_NO_DATA:
                if (BuildConfig.DEBUG)
                    Log.d(TripleSolitaireActivity.TAG, "State not loaded - network error with no data");
                // can't reach cloud, and we have no local state. Warn user that
                // they may not see their existing progress, but any new progress won't be lost.
                // TODO warn about network error
                break;
            case AppStateStatusCodes.STATUS_NETWORK_ERROR_STALE_DATA:
                if (BuildConfig.DEBUG)
                    Log.d(TripleSolitaireActivity.TAG, "State not loaded - network error with stale data");
                // can't reach cloud, but we have locally cached data.
                // TODO warn about stale data
                break;
            case AppStateStatusCodes.STATUS_CLIENT_RECONNECT_REQUIRED:
                if (BuildConfig.DEBUG)
                    Log.d(TripleSolitaireActivity.TAG, "State not loaded - reconnect required");
                // need to reconnect AppStateClient
                reconnectClient();
                break;
            default:
                // TODO warn about generic error
                break;
        }
    }

    private synchronized void saveToCloud() {
        AppStateManager.update(getApiClient(), OUR_STATE_KEY, stats.toBytes());
        // Check win streak achievements
        final int longestWinStreak = stats.getLongestWinStreak();
        if (BuildConfig.DEBUG)
            Log.d(TripleSolitaireActivity.TAG, "Longest Win Streak: " + longestWinStreak);
        if (longestWinStreak >= 1)
            Games.Achievements.unlock(getApiClient(), getString(R.string.achievement_youre_a_winner));
        if (longestWinStreak >= 5)
            Games.Achievements.unlock(getApiClient(), getString(R.string.achievement_streaker));
        if (longestWinStreak >= 10)
            Games.Achievements.unlock(getApiClient(), getString(R.string.achievement_master_streaker));
        // Check minimum moves achievements
        final int minimumMovesUnsynced = stats.getMinimumMoves(true);
        if (minimumMovesUnsynced < Integer.MAX_VALUE) {
            if (BuildConfig.DEBUG)
                Log.d(TripleSolitaireActivity.TAG, "Minimum Moves Unsynced: " + minimumMovesUnsynced);
            Games.Leaderboards.submitScore(getApiClient(), getString(R.string.leaderboard_moves),
                    minimumMovesUnsynced);
        }
        final int minimumMoves = stats.getMinimumMoves(false);
        if (BuildConfig.DEBUG)
            Log.d(TripleSolitaireActivity.TAG, "Minimum Moves: " + minimumMoves);
        if (minimumMoves < 400)
            Games.Achievements.unlock(getApiClient(), getString(R.string.achievement_figured_it_out));
        if (minimumMoves < 300)
            Games.Achievements.unlock(getApiClient(), getString(R.string.achievement_no_mistakes));
        // Check shortest time achievements
        final int shortestTimeUnsynced = stats.getShortestTime(true);
        if (shortestTimeUnsynced < Integer.MAX_VALUE) {
            if (BuildConfig.DEBUG)
                Log.d(TripleSolitaireActivity.TAG, "Shortest Time Unsynced (minutes): " +
                        (double) shortestTimeUnsynced / 60);
            Games.Leaderboards.submitScore(getApiClient(), getString(R.string.leaderboard_time),
                    shortestTimeUnsynced * DateUtils.SECOND_IN_MILLIS);
        }
        final int shortestTime = stats.getShortestTime(false);
        if (BuildConfig.DEBUG)
            Log.d(TripleSolitaireActivity.TAG, "Shortest Time (minutes): " + (double) shortestTime / 60);
        if (shortestTime < 15 * 60)
            Games.Achievements.unlock(getApiClient(), getString(R.string.achievement_quarter_hour));
        if (shortestTime < 10 * 60)
            Games.Achievements.unlock(getApiClient(), getString(R.string.achievement_single_digits));
        if (shortestTime < 8 * 60)
            Games.Achievements.unlock(getApiClient(), getString(R.string.achievement_speed_demon));
        ContentValues values = new ContentValues();
        values.put(GameContract.Games.COLUMN_NAME_SYNCED, true);
        mAsyncQueryHandler.startUpdate(0, null, GameContract.Games.CONTENT_URI, values,
                GameContract.Games.COLUMN_NAME_SYNCED + "=0", null);
        // Load achievements to handle incremental achievements
        Games.Achievements.load(getApiClient(), false)
                .setResultCallback(new ResultCallback<Achievements.LoadAchievementsResult>() {
                    @Override
                    public void onResult(final Achievements.LoadAchievementsResult loadAchievementsResult) {
                        onAchievementsLoaded(loadAchievementsResult);
                    }
                });
        mPendingUpdateState = false;
    }
}