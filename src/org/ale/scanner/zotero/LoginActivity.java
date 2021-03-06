/** 
 * Copyright 2011 John M. Schanck
 * 
 * ScannerForZotero is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ScannerForZotero is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ScannerForZotero.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.ale.scanner.zotero;

import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.commonshttp.CommonsHttpOAuthProvider;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;
import oauth.signpost.exception.OAuthNotAuthorizedException;
import oauth.signpost.http.HttpParameters;

import org.ale.scanner.zotero.data.Account;
import org.ale.scanner.zotero.data.Database;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;

public class LoginActivity extends Activity {

    public static final String PREFS_NAME = "config";

    public static final String INTENT_EXTRA_NEW_ACCT = "NEW_API_KEY";
    public static final String INTENT_EXTRA_CLEAR_FIELDS = "CLEAR_FIELDS";

    public static final String RECREATE_CURRENT_DISPLAY = "CURDISP";
    public static final String RECREATE_ACCOUNT = "ACCT";

    // OAuth URLs
    public static final String SCANNER_SCHEME = "zotero";
    public static final String OAUTH_CLIENT_KEY = "1ec29cd0abcb23afe808";
    public static final String OAUTH_CLIENT_SECRET = "80be334a594978d3ab87";
    public static final String OAUTH_BASE_URL = "https://www.zotero.org";
    public static final String OAUTH_PARAMS = 
            "?library_access=1&notes_access=0&write_access=1&all_groups=write";
    public static final String OAUTH_REQ_TOKEN_URL = 
            OAUTH_BASE_URL + "/oauth/request" + OAUTH_PARAMS;
    public static final String OAUTH_ACS_TOKEN_URL =
            OAUTH_BASE_URL + "/oauth/access" + OAUTH_PARAMS;
    public static final String OAUTH_AUTHORIZE_URL =
            OAUTH_BASE_URL + "/oauth/authorize" + OAUTH_PARAMS;
    public static final String OAUTH_CALLBACK_URL =
            SCANNER_SCHEME + "://oauth";

    // Subactivity result codes
    public static final int RESULT_APIKEY = 0;

    // Transitions to make on receiving cursor
    public static final int RECV_CURSOR_NOTHING = -1;
    public static final int RECV_CURSOR_PROMPT = 0;
    public static final int RECV_CURSOR_LOGIN = 1;

    private static String mOAuthToken = null;
    private static String mOAuthTokenSecret = null;

    // Transient state
    private Account mAccount;

    private boolean mLoggedIn;

    private boolean mRememberMe;

    private boolean mPaused = false;

    private Cursor mAcctCursor = null;

    private AlertDialog mAlertDialog = null;

    private int mOnRecvCursor = RECV_CURSOR_NOTHING;

    private Handler mHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login);
        mHandler = new Handler();

        // Query the database for saved keys (separate thread)
        getSavedKeys();

        // Load the saved state, fills user/key fields, sets checkboxes etc.
        loadConfig();

        // All listeners are defined at the bottom of this file.
        // Login option buttons:
        findViewById(R.id.login_saved_key).setOnClickListener(loginButtonListener);
        findViewById(R.id.login_by_web).setOnClickListener(loginButtonListener);
        findViewById(R.id.login_manually).setOnClickListener(loginButtonListener);
        findViewById(R.id.login_submit).setOnClickListener(loginButtonListener);
        findViewById(R.id.login_cancel).setOnClickListener(loginButtonListener);

        // The checkbox determines whether the alias field is visible
        // and sets mRememberMe
        ((CheckBox)findViewById(R.id.save_login)).setOnCheckedChangeListener(cbListener);
        ((CheckBox)findViewById(R.id.save_login)).setChecked(mRememberMe);

        if (savedInstanceState != null){
            // Set the displayed screen (login options or editables)
            int curView = savedInstanceState.getInt(RECREATE_CURRENT_DISPLAY, 0);
            ((SafeViewFlipper)findViewById(R.id.login_view_flipper))
                .setDisplayedChild(curView);
            setUserAndKey((Account) savedInstanceState.getParcelable(RECREATE_ACCOUNT));
        }
    }

    @Override
    public void onResume(){
        super.onResume();

        mPaused = false;

        Intent intent = getIntent();
        if(intent != null) {
            Uri uri = intent.getData();
            if (uri != null)
                handleOAuthCallback(uri);

            Bundle extras = intent.getExtras();
            if (extras != null)
                handleIntentExtras(extras);
            setIntent(null);
        }

        if(!TextUtils.isEmpty(mAccount.getKey()))
            showNext();

        if(mLoggedIn){ // Might still be logged in from last session
            if(mAcctCursor != null){
                doLogin();
            }else{
                mOnRecvCursor = RECV_CURSOR_LOGIN;
            }
        }

        // Display any dialogs we were displaying before being destroyed
        switch(Dialogs.displayedDialog) {
        case(Dialogs.DIALOG_NO_DIALOG):
            break;
        case(Dialogs.DIALOG_SAVED_KEYS):
            mOnRecvCursor = RECV_CURSOR_PROMPT;
            break;
        }
    }

    @Override
    public void onPause(){
        super.onPause();

        saveConfig();
        if(mAlertDialog != null){ // Prevent dialog windows from leaking
            mAlertDialog.dismiss();
            mAlertDialog = null;
        }
        mPaused = true;
    }

    private void fetchKeyOAuth() {
        try {
            CommonsHttpOAuthConsumer consumer = new CommonsHttpOAuthConsumer(
                OAUTH_CLIENT_KEY, OAUTH_CLIENT_SECRET);
            
            CommonsHttpOAuthProvider provider = new CommonsHttpOAuthProvider(
                OAUTH_REQ_TOKEN_URL, OAUTH_ACS_TOKEN_URL, OAUTH_AUTHORIZE_URL);
            provider.setOAuth10a(true);

            String authUrl = provider.retrieveRequestToken(
                consumer, OAUTH_CALLBACK_URL);

            mOAuthToken = consumer.getToken();
            mOAuthTokenSecret = consumer.getTokenSecret();
            saveConfig();

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            intent.setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            | Intent.FLAG_ACTIVITY_NO_HISTORY);
            startActivity(intent);
        } catch (OAuthMessageSignerException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (OAuthNotAuthorizedException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (OAuthExpectationFailedException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (OAuthCommunicationException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handleOAuthCallback(Uri uri) {
        if(uri == null)
            return;

        if(!uri.getHost().equals("oauth"))
            return;

        final String verifier = uri
                .getQueryParameter(oauth.signpost.OAuth.OAUTH_VERIFIER);

        if(verifier == null)
            return;
        
        new Thread(new Runnable() {
        public void run() {
            try {
                CommonsHttpOAuthConsumer consumer = new CommonsHttpOAuthConsumer(
                    OAUTH_CLIENT_KEY, OAUTH_CLIENT_SECRET);
                CommonsHttpOAuthProvider provider = new CommonsHttpOAuthProvider(
                    OAUTH_REQ_TOKEN_URL, OAUTH_ACS_TOKEN_URL, OAUTH_AUTHORIZE_URL);
                provider.setOAuth10a(true);

                if (mOAuthToken == null || mOAuthTokenSecret == null) {
                    SharedPreferences prefs =
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    mOAuthToken =
                        prefs.getString(getString(R.string.pref_token), "");
                    mOAuthTokenSecret =
                        prefs.getString(
                            getString(R.string.pref_token_secret), "");
                }

                consumer.setTokenWithSecret(mOAuthToken, mOAuthTokenSecret);
                provider.retrieveAccessToken(consumer, verifier);

                HttpParameters params = provider.getResponseParameters();
                String userSecret = consumer.getTokenSecret();
                String userID = params.getFirst("userID");
                String userName = params.getFirst("username") + " [" + userSecret.substring(0, 5) + "]";

                Account acct = new Account(userName, userID, userSecret);
                setUserAndKey(acct);
                saveConfig();

                Intent intent = new Intent(LoginActivity.this, LoginActivity.class);
                intent.putExtra(INTENT_EXTRA_NEW_ACCT, acct);
                LoginActivity.this.startActivity(intent);
                startActivity(intent);
            } catch (OAuthMessageSignerException e) {
                Toast.makeText(LoginActivity.this,
                        e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (OAuthNotAuthorizedException e) {
                Toast.makeText(LoginActivity.this,
                        e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (OAuthExpectationFailedException e) {
                Toast.makeText(LoginActivity.this,
                        e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (OAuthCommunicationException e) {
                Toast.makeText(LoginActivity.this,
                        e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }}).run();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (intent == null)
            return;

        setIntent(intent);
    }

    private void handleIntentExtras(Bundle extras) {
        boolean clearFields = false;
        Account newAcct = null;

        if(extras != null) {
            newAcct = extras.getParcelable(INTENT_EXTRA_NEW_ACCT);
            clearFields = extras.getBoolean(INTENT_EXTRA_CLEAR_FIELDS, false);
        }

        // If called from Main via "Log out", we need to clear the login info
        if (clearFields) {
            mOAuthToken = null;
            mOAuthTokenSecret = null;
            setUserAndKey("","","");
            mLoggedIn = false;
        }

        // If called by OAuth callback we need to set the login info
        if (newAcct != null) {
            mOAuthToken = null;
            mOAuthTokenSecret = null;
            setUserAndKey(newAcct);
            saveConfig();
            showNext();
            mLoggedIn = false;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);
        state.putInt(RECREATE_CURRENT_DISPLAY, 
             ((SafeViewFlipper)findViewById(R.id.login_view_flipper)).getDisplayedChild());
        state.putParcelable(RECREATE_ACCOUNT, mAccount);
    }

    private void doLogin(){ 
        // mAcctCursor MUST be open before this is called
        if(mPaused) {
            return;
        }
        extractCredentials();
        boolean validId = validateUserId();       // These have side effects
        boolean validKey = validateApiKey();      // (error flags on textviews)

        if(!(validId && validKey)){
            Toast.makeText(LoginActivity.this, "Invalid credentials",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Try to find a matching account in the database
        int acctId = Account.NOT_IN_DATABASE;
        if(mAcctCursor.getCount() > 0){
            // Check if key is already in database
            String pKey;
            String keyToInsert = mAccount.getKey();
            mAcctCursor.moveToFirst();
            while(mAcctCursor.isAfterLast() == false){
                pKey = mAcctCursor.getString(Database.ACCOUNT_KEY_INDEX);
                if(TextUtils.equals(pKey, keyToInsert)){
                    acctId = mAcctCursor.getInt(Database.ACCOUNT_ID_INDEX);
                    break;
                }
                mAcctCursor.moveToNext();
            }
        }

        // Insert new key into database
        if(mRememberMe && acctId == Account.NOT_IN_DATABASE){
            // Yes, this blocks the UI thread.
            ContentValues values = mAccount.toContentValues();
            Uri result = getContentResolver().insert(Database.ACCOUNT_URI, values);
            acctId = Integer.parseInt(result.getLastPathSegment());
        }

        mAccount.setDbId(acctId);

        mLoggedIn = mRememberMe; // This is the mLoggedIn value that gets saved
                                 // to prefs. If the user didn't check "Remember Me"
                                 // we won't automatically log them in.

        // Transition to Main activity
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(MainActivity.INTENT_EXTRA_ACCOUNT, mAccount);
        LoginActivity.this.startActivity(intent);
        finish();
    }

    private void getSavedKeys(){
        // Ignore this call if we have an open cursor
        if(mAcctCursor != null && !mAcctCursor.isClosed())
            return;

        // This might create or upgrade the database, so it is
        // run in a separate thread.
        new Thread(new Runnable() {
            public void run() {
                String[] projection = new String[] { Account._ID,
                        Account.COL_ALIAS, Account.COL_UID, Account.COL_KEY };

                Cursor cursor = getContentResolver().query(
                        Database.ACCOUNT_URI, projection, null, null,
                        Account._ID + " ASC");

                gotAccountCursor(cursor);
            }
        }).start();
    }

    private void gotAccountCursor(final Cursor c){
        mHandler.post(new Runnable() {
            @SuppressWarnings("deprecation")
            public void run() {
                mAcctCursor = c;
                startManagingCursor(mAcctCursor); 

                findViewById(R.id.login_saved_key)
                        .setVisibility((mAcctCursor.getCount() > 0)
                                ? View.VISIBLE : View.GONE);

                switch(mOnRecvCursor){
                // On an activity recreate (following orientation change, etc)
                // we need to immediately call promptToUseSavedKey if the dialog
                // was displayed prior to the activity being destroyed.
                case RECV_CURSOR_PROMPT:
                    mAlertDialog = Dialogs.promptToUseSavedKey(
                                        LoginActivity.this, mAcctCursor);
                    break;
                // And sometimes we're resuming a previous session and just need the
                // cursor to determine the account id
                case RECV_CURSOR_LOGIN:
                    doLogin();
                    break;
                }
                mOnRecvCursor = RECV_CURSOR_NOTHING;
            }
        });
    }

    /* Saved Preferences */
    protected void loadConfig(){
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String alias = prefs.getString(getString(R.string.pref_alias), "");
        String uid = prefs.getString(getString(R.string.pref_userid), "");
        String key = prefs.getString(getString(R.string.pref_apikey), "");
        mRememberMe = prefs.getBoolean(getString(R.string.pref_rememberme), true);
        mLoggedIn = prefs.getBoolean(getString(R.string.pref_loggedin), false);
        mOAuthToken = prefs.getString(getString(R.string.pref_token), null);
        mOAuthTokenSecret = prefs.getString(getString(R.string.pref_token_secret), null);

        setUserAndKey(alias, uid, key);
    }

    protected void saveConfig(){
        SharedPreferences config = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = config.edit();

        editor.putString(getString(R.string.pref_alias), mAccount.getAlias());
        editor.putString(getString(R.string.pref_userid), mAccount.getUid());
        editor.putString(getString(R.string.pref_apikey), mAccount.getKey());

        editor.putBoolean(getString(R.string.pref_rememberme), mRememberMe);

        editor.putBoolean(getString(R.string.pref_loggedin), mLoggedIn);

        editor.putString(getString(R.string.pref_token), mOAuthToken);
        editor.putString(getString(R.string.pref_token_secret), mOAuthTokenSecret);

        editor.commit();
    }

    protected void setUserAndKey(String alias, String uid, String key){
        if(TextUtils.isEmpty(alias))
            alias = "New User";
        setUserAndKey(new Account(alias, uid, key));
    }

    protected void setUserAndKey(Account acct){
        mAccount = acct;
        ((EditText) findViewById(R.id.userid_edittext)).setText(acct.getUid());
        ((EditText) findViewById(R.id.apikey_edittext)).setText(acct.getKey());
        validateUserId();
        validateApiKey();
    }

    /**
     * Extracts the user's id and key from the login form. Called from doLogin,
     * so all login methods should take measures to ensure that the form is
     * properly filled before calling doLogin, such as by calling setUserAndKey
     */
    private void extractCredentials() {
        EditText uid_et = (EditText) findViewById(R.id.userid_edittext);
        EditText key_et = (EditText) findViewById(R.id.apikey_edittext);
        mAccount.setUid(uid_et.getText().toString());
        mAccount.setKey(key_et.getText().toString());
    }

    /* Input validation */
    private boolean validateUserId(){
        boolean valid = mAccount.hasValidUserId();
        if(valid || TextUtils.isEmpty(mAccount.getUid()))
            ((EditText) findViewById(R.id.userid_edittext)).setError(null);
        else
            ((EditText) findViewById(R.id.userid_edittext)).setError("Invalid user ID");
        return valid;
    }

    private boolean validateApiKey(){
        boolean valid = mAccount.hasValidApiKey();
        if(valid || TextUtils.isEmpty(mAccount.getKey()))
            ((EditText) findViewById(R.id.apikey_edittext)).setError(null);
        else
            ((EditText) findViewById(R.id.apikey_edittext)).setError("Invalid API key");
        return valid;
    }


    /* View Flipping */
    protected void showPrevious() {
        SafeViewFlipper vf = (SafeViewFlipper)findViewById(R.id.login_view_flipper);
        if(vf.getCurrentView().getId() == R.id.login_view_editables){
            vf.setInAnimation(AnimationUtils.loadAnimation(LoginActivity.this, R.anim.slide_in_previous));
            vf.setOutAnimation(AnimationUtils.loadAnimation(LoginActivity.this, R.anim.slide_out_previous));
            vf.showPrevious();
        }
    }

    protected void showNext() {
        SafeViewFlipper vf = (SafeViewFlipper)findViewById(R.id.login_view_flipper);
        if(vf.getCurrentView().getId() == R.id.login_view_options){
            vf.setInAnimation(AnimationUtils.loadAnimation(LoginActivity.this, R.anim.slide_in_next));
            vf.setOutAnimation(AnimationUtils.loadAnimation(LoginActivity.this, R.anim.slide_out_next));
            vf.showNext();
        }
    }

    // Catch back button if we're showing the editable fields
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)  {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            SafeViewFlipper vf = ((SafeViewFlipper)findViewById(R.id.login_view_flipper));
            if(vf.getCurrentView().getId() == R.id.login_view_editables){
                showPrevious();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }


    /* Options Menu */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu_login, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.opt_manage_keys:
            Intent intent = new Intent(LoginActivity.this, ManageAccountsActivity.class);
            startActivity(intent);
            return true;
        case R.id.opt_use_saved_key:
            if (mAcctCursor != null) {
                mAlertDialog = Dialogs.promptToUseSavedKey(
                                    LoginActivity.this, mAcctCursor);
            } else {
                mOnRecvCursor = RECV_CURSOR_PROMPT;
            }
            return true;
        case R.id.opt_help:
            mAlertDialog = Dialogs.showLoginHelp(LoginActivity.this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    /* Interface listeners */
    private final Button.OnClickListener loginButtonListener = new Button.OnClickListener() {
        public void onClick(View v) {
            switch(v.getId()){
            case R.id.login_saved_key:
                if (mAcctCursor != null) {
                    mAlertDialog = Dialogs.promptToUseSavedKey(
                                        LoginActivity.this, mAcctCursor);
                }else{
                    mOnRecvCursor = RECV_CURSOR_PROMPT;
                }
                break;

            case R.id.login_by_web:
                Toast.makeText(LoginActivity.this, "Please wait.", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                        public void run() {
                            fetchKeyOAuth();
                        }
                    }).start();
                // mAlertDialog = Dialogs.informUserAboutLogin(LoginActivity.this);
                break;
            
            case R.id.login_manually:
                setUserAndKey("","","");
                showNext();
                break;

            case R.id.login_submit:
                if(mAcctCursor != null){
                    doLogin();
                }else{
                    mOnRecvCursor = RECV_CURSOR_LOGIN;
                }
                break;

            case R.id.login_cancel:
                setUserAndKey("", "", "");
                break;
            }
        }
    };

    private final CheckBox.OnCheckedChangeListener cbListener = new CheckBox.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton checkbox, boolean checked) {
            mRememberMe = checked;
        }
    };
}