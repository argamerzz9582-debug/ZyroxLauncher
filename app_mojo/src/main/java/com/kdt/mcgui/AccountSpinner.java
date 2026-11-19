package com.kdt.mcgui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.BackgroundLogin;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.listener.LoginListener;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.extra.ExtraListener;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;

import fr.spse.extended_view.ExtendedTextView;
import git.artdeell.mojo.R;

public class AccountSpinner extends AppCompatSpinner implements LoginListener, AdapterView.OnItemSelectedListener, ValueAnimator.AnimatorUpdateListener {
    private static volatile boolean sLoginInFlight = false;

    /** Returns {@code true} while an account creation or OAuth token exchange is running. */
    public static boolean isLoginInFlight() {
        return sLoginInFlight;
    }

    private Adapter mAdapter;
    private boolean mSuppressSelectionCallback = false;
    /* Login progress bar stuff */
    private int mMaxSteps = 5;
    private final ValueAnimator mLoginStepAnimator = ValueAnimator.ofFloat(mMaxSteps);
    private final Paint mLoginBarPaint = new Paint();
    private float mLoginStep;

    class LoginExtraListener implements ExtraListener<String> {
        private final AuthType mAuthType;

        LoginExtraListener(AuthType mAuthType) {
            this.mAuthType = mAuthType;
        }

        @Override
        public boolean onValueSet(String key, @NonNull String value) {
            sLoginInFlight = true;
            mLoginBarPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.zyrox_amber_bright));
            BackgroundLogin backgroundLogin = mAuthType.createAuth();
            backgroundLogin.createAccount(AccountSpinner.this, value);
            return false;
        }
    }

    /* Login listeners */
    private final ExtraListener<String> mMicrosoftLoginListener = new LoginExtraListener(AuthType.MICROSOFT);
    private final ExtraListener<String> mElyByLoginListener = new LoginExtraListener(AuthType.ELY_BY);
    private final ExtraListener<String[]> mMojangLoginListener = (key, value) -> {
        sLoginInFlight = true;
        try {
            Account account = Accounts.create(acc-> acc.username = value[0]);
            onLoginDone(account);
        }catch (IOException e) {
            onLoginError(e);
        }
        return false;
    };

    /* Account main menu refresh listener */
    private final ExtraListener<Boolean> mRefreshAccountsListener = (k,v)->{
        reload();
        return false;
    };

    public AccountSpinner(@NonNull Context context, int mode) {
        super(context, mode);
        init();
    }

    public AccountSpinner(@NonNull Context context) {
        super(context);
        init();
    }

    public AccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public AccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int mode) {
        super(context, attrs, defStyleAttr, mode);
        init();
    }

    public AccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int mode, Resources.Theme popupTheme) {
        super(context, attrs, defStyleAttr, mode, popupTheme);
        init();
    }



    private void init() {
        mAdapter = new Adapter(getContext());
        setAdapter(mAdapter);
        setOnItemSelectedListener(this);
        reload();

        setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.bg_zyrox_input, getContext().getTheme()));
        setPopupBackgroundDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.bg_zyrox_glass_modal, getContext().getTheme()));
        mLoginBarPaint.setAntiAlias(true);
        mLoginBarPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        mLoginBarPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.zyrox_amber_bright));
        mLoginBarPaint.setStrokeWidth(Math.max(2f, 2f * getResources().getDisplayMetrics().density));
        mLoginStepAnimator.addUpdateListener(this);
        mLoginStep = mMaxSteps;

        ExtraCore.addExtraListener(ExtraConstants.MOJANG_LOGIN_TODO, mMojangLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.MICROSOFT_LOGIN_TODO, mMicrosoftLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.ELYBY_LOGIN_TODO, mElyByLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.REFRESH_ACCOUNT_SPINNER, mRefreshAccountsListener);
    }

    /**
     * Releases static ExtraCore listeners and active login animator when the host Activity finishes.
     */
    public void cleanUpListeners() {
        mLoginStepAnimator.cancel();
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.MOJANG_LOGIN_TODO, mMojangLoginListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.MICROSOFT_LOGIN_TODO, mMicrosoftLoginListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.ELYBY_LOGIN_TODO, mElyByLoginListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, mRefreshAccountsListener);
    }

    private void reload() {
        MojoApplication.sExecutorService.execute(()->{
            try {
                Accounts accounts = Accounts.load();
                Tools.runOnUiThread(()->refresh(accounts));
            } catch (IOException e) {
                android.util.Log.e("AccountSpinner", "Failed to load saved accounts", e);
            }
        });
    }

    private void refresh(Accounts accounts) {
        if (accounts == null || accounts.accounts == null) return;
        mAdapter.setNotifyOnChange(false);
        mAdapter.clear();
        mAdapter.add(null);
        mAdapter.setNotifyOnChange(true);
        mAdapter.addAll(accounts.accounts);

        mSuppressSelectionCallback = true;
        try {
            if(accounts.accounts.isEmpty()) {
                // Never auto-open SelectAuthFragment during programmatic empty reload;
                // OnboardingWizardFragment handles first-time login in a single unified flow,
                // and tapping "+ Add account" explicitly opens auth on demand.
                setSelection(0, false);
            } else {
                int clampedIndex = Math.max(0, Math.min(accounts.selectionIndex, accounts.accounts.size() - 1));
                setSelection(clampedIndex + 1, false);
                Object selected = getSelectedItem();
                if (selected instanceof Account) {
                    refreshAccount((Account) selected);
                }
            }
        } finally {
            post(() -> mSuppressSelectionCallback = false);
        }
    }

    private void refreshAccount(Account account) {
        // Wait until all tasks (including other possible login tasks) are done before
        // attempting to refresh the account.
        ProgressKeeper.waitUntilDone(()->{
            // Reload the account data before attempting to refresh (what if it was already refreshed in the background?)
            Account refreshAccount = account.reload();
            if(refreshAccount == null) return;
            AuthType authType = refreshAccount.authType;
            if(authType.requiresLogin() && System.currentTimeMillis() > refreshAccount.expiresAt) {
                authType.createAuth().refreshAccount(this, refreshAccount);
            }
        });
    }

    private void dismissPopup() {
        onDetachedFromWindow();
        onAttachedToWindow();
    }

    private void createAccount() {
        setSelection(0);
        dismissPopup();
        ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        // Only render sleek inset progress line while actively authenticating (keeps crystal glass rim clean at rest)
        if (mMaxSteps > 0 && mLoginStep > 0f && mLoginStep < mMaxSteps) {
            float density = getResources().getDisplayMetrics().density;
            float inset = 12f * density;
            float usableWidth = Math.max(0f, getWidth() - inset * 2f);
            float bottom = getHeight() - mLoginBarPaint.getStrokeWidth();
            float lineFillPercent = Math.max(0f, Math.min(1f, mLoginStep / mMaxSteps));
            canvas.drawLine(inset, bottom, inset + lineFillPercent * usableWidth, bottom, mLoginBarPaint);
        }
    }

    @Override
    public void onLoginDone(Account account) {
        sLoginInFlight = false;
        mLoginStep = mMaxSteps;
        invalidate();

        Toast.makeText(getContext(), R.string.main_login_done, Toast.LENGTH_SHORT).show();
        Accounts.setCurrent(account);
        git.artdeell.mojo.fragments.OnboardingWizardFragment.onAccountAuthenticated();
        reload();
    }


    @Override
    public void onLoginError(Throwable errorMessage) {
        sLoginInFlight = false;
        mLoginBarPaint.setColor(Color.RED);
        invalidate();

        Context context = getContext();
        if(!(context instanceof Activity)) return;
        if(context instanceof LifecycleOwner) {
            LifecycleOwner lifecycleOwner = (LifecycleOwner) context;
            Lifecycle.State state = lifecycleOwner.getLifecycle().getCurrentState();
            if(state != Lifecycle.State.RESUMED) return;
        }

        Tools.showErrorRemote(errorMessage);
    }

    @Override
    public void onLoginProgress(int step) {
        mLoginStepAnimator.cancel();
        mLoginStepAnimator.setFloatValues(mLoginStep, step);
        mLoginStepAnimator.start();
    }

    @Override
    public void setMaxLoginProgress(int max) {
        mMaxSteps = max;
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        if (mSuppressSelectionCallback) {
            return;
        }
        Account account = mAdapter.getItem(i);
        if(account == null) {
            if(i == 0) {
                // Only trigger createAccount when explicitly picked from a populated dropdown;
                // when empty (count == 1), populateView's click listener handles direct user taps
                // without spuriously opening a second login screen on Activity/layout start.
                if (mAdapter.getCount() > 1) {
                    createAccount();
                }
            }else {
                Tools.showError(adapterView.getContext(), new NullPointerException());
            }
            return;
        }
        Accounts.setCurrent(account);
        refreshAccount(account);
        dismissPopup();
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {}

    @Override
    public void onAnimationUpdate(@NonNull ValueAnimator valueAnimator) {
        mLoginStep = (float)valueAnimator.getAnimatedValue();
        invalidate();
    }

    private class Adapter extends ArrayAdapter<Account> {
        private final HashMap<Integer, BitmapDrawable> mSkinHeadCache = new HashMap<>();
        private final LayoutInflater mInflater;


        public Adapter(@NonNull Context context) {
            super(context, R.layout.item_account);
            mInflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if(convertView == null) {
                convertView = mInflater.inflate(R.layout.item_account, parent, false);
            }
            populateView(convertView, position, false);
            return convertView;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if(convertView == null) {
                convertView = mInflater.inflate(R.layout.item_account, parent, false);
            }
            populateView(convertView, position, true);
            return convertView;
        }

        private void populateView(View view, int position, boolean isDropDown) {
            Resources resources = getResources();
            Resources.Theme theme = getContext().getTheme();

            ExtendedTextView textview = view.findViewById(R.id.account_item);
            android.widget.TextView badgeView = view.findViewById(R.id.account_type_badge);
            ImageView deleteButton = view.findViewById(R.id.delete_account_button);

            if(position == 0) {
                // "Add account" button
                Drawable plusDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_add, theme);
                textview.setCompoundDrawablesRelative(plusDrawable, null, null, null);
                textview.setText(R.string.main_add_account);
                textview.setTextColor(resources.getColor(R.color.zyrox_amber));
                if (badgeView != null) badgeView.setVisibility(View.GONE);
                deleteButton.setVisibility(View.GONE);
                if(isDropDown || getCount() == 1) view.setOnClickListener(v-> createAccount());
                return;
            }
            view.setOnClickListener(null);
            view.setClickable(false);
            textview.setTextColor(resources.getColor(R.color.zyrox_text_primary));

            if(isDropDown) {
                deleteButton.setVisibility(View.VISIBLE);
                deleteButton.setOnClickListener((v)->showDeleteDialog(v.getContext(), position));
            }else {
                deleteButton.setVisibility(View.GONE);
            }


            Account account = Objects.requireNonNull(getItem(position));

            int authTypeResource = account.authType.iconResource;

            Drawable authType = null;
            if(authTypeResource != 0) {
                authType = ResourcesCompat.getDrawable(resources, authTypeResource, theme);
            }

            Drawable avatarDrawable;
            int headCacheHash = System.identityHashCode(account);
            BitmapDrawable accountHead = mSkinHeadCache.get(headCacheHash);
            if (accountHead == null){
                Bitmap accountSkinFace = account.getSkinFace();
                if (accountSkinFace != null) {
                    accountHead = new BitmapDrawable(resources, accountSkinFace);
                    mSkinHeadCache.put(headCacheHash, accountHead);
                }
            }
            if (accountHead != null) {
                avatarDrawable = accountHead;
            } else {
                avatarDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_default_steve_face, theme);
            }

            if (badgeView != null) {
                badgeView.setVisibility(View.VISIBLE);
                if (account.authType == AuthType.MICROSOFT) {
                    badgeView.setText(R.string.zyrox_badge_microsoft);
                } else if (account.authType == AuthType.ELY_BY) {
                    badgeView.setText(R.string.zyrox_badge_elyby);
                } else {
                    badgeView.setText(R.string.zyrox_badge_offline);
                }
            }

            textview.setText(account.username);
            textview.setCompoundDrawablesRelative(avatarDrawable, null, authType, null);
        }

        private void showDeleteDialog(Context context, int position) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.warning_remove_account)
                    .setPositiveButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.global_delete, (dialog, which) -> {
                        Account account = getItem(position);
                        Accounts.delete(account);
                        reload();
                    })
                    .show();
        }
    }
}
