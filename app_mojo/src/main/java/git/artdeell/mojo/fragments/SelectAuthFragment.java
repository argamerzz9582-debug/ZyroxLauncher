package git.artdeell.mojo.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.ProgressLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;

/**
 * Page 5: Zyrox Account & Skin Vault — Centered Glass Modal with
 * isometric skin preview, PREMIUM/OFFLINE badge, saved account switcher/delete,
 * Microsoft & Ely.by OAuth triggers, and direct inline offline profile creation.
 */
public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private ImageView mSkinHeadView;
    private TextView mUsernameView;
    private TextView mBadgeView;
    private TextView mEmptyAccountsView;
    private RecyclerView mAccountsRecycler;
    private EditText mOfflineUsernameEdit;

    private final List<Account> mAccounts = new ArrayList<>();
    private int mSelectedIndex = -1;
    private VaultAccountAdapter mAdapter;

    public SelectAuthFragment() {
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mMicrosoftButton = view.findViewById(R.id.button_microsoft_authentication);
        Button mLocalButton = view.findViewById(R.id.button_local_authentication);
        Button mElyByButton = view.findViewById(R.id.button_elyby_authentication);

        mSkinHeadView = view.findViewById(R.id.iv_vault_skin_head);
        mUsernameView = view.findViewById(R.id.tv_vault_username);
        mBadgeView = view.findViewById(R.id.tv_vault_badge);
        mEmptyAccountsView = view.findViewById(R.id.tv_vault_empty_accounts);
        mAccountsRecycler = view.findViewById(R.id.rv_vault_accounts);
        mOfflineUsernameEdit = view.findViewById(R.id.login_edit_email);

        if (mAccountsRecycler != null) {
            mAccountsRecycler.setLayoutManager(new LinearLayoutManager(view.getContext()));
            mAdapter = new VaultAccountAdapter();
            mAccountsRecycler.setAdapter(mAdapter);
        }

        mMicrosoftButton.setOnClickListener(v -> launchAuthFragment(MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG));
        mElyByButton.setOnClickListener(v -> launchAuthFragment(ElyByLoginFragment.class, ElyByLoginFragment.TAG));

        mLocalButton.setOnClickListener(v -> submitOfflineAccount(v.getContext()));
        if (mOfflineUsernameEdit != null) {
            mOfflineUsernameEdit.setOnEditorActionListener((tv, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitOfflineAccount(tv.getContext());
                    return true;
                }
                return false;
            });
        }

        loadSavedAccounts();
    }

    private void submitOfflineAccount(Context context) {
        if (mOfflineUsernameEdit == null) {
            launchAuthFragment(LocalLoginFragment.class, LocalLoginFragment.TAG);
            return;
        }
        String username = mOfflineUsernameEdit.getText().toString().trim();
        if (username.isEmpty()) {
            mOfflineUsernameEdit.requestFocus();
            Tools.dialog(context,
                    context.getString(R.string.local_login_bad_username_title),
                    context.getString(R.string.local_login_bad_username_text));
            return;
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            Tools.dialog(context,
                    context.getString(R.string.local_login_bad_username_title),
                    context.getString(R.string.local_login_bad_username_text));
            return;
        }
        ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{username, ""});
        Tools.backToMainMenu(requireActivity());
    }

    private void loadSavedAccounts() {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                Accounts loaded = Accounts.load();
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mAccounts.clear();
                    mAccounts.addAll(loaded.accounts);
                    mSelectedIndex = mAccounts.isEmpty() ? -1 :
                            Math.max(0, Math.min(mAccounts.size() - 1, loaded.selectionIndex));
                    if (mAdapter != null) mAdapter.notifyDataSetChanged();
                    updatePreviewPanel();
                });
            } catch (IOException ignored) {
            }
        });
    }

    private void updatePreviewPanel() {
        if (mEmptyAccountsView != null && mAccountsRecycler != null) {
            boolean empty = mAccounts.isEmpty();
            mEmptyAccountsView.setVisibility(empty ? View.VISIBLE : View.GONE);
            mAccountsRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (mSkinHeadView == null || mUsernameView == null || mBadgeView == null) return;

        if (mSelectedIndex < 0 || mSelectedIndex >= mAccounts.size()) {
            mSkinHeadView.setImageResource(R.drawable.ic_default_steve_head);
            mUsernameView.setText(R.string.main_add_account);
            mBadgeView.setText(R.string.zyrox_badge_no_account);
            return;
        }

        Account active = mAccounts.get(mSelectedIndex);
        Bitmap face = active.getSkinFace();
        if (face != null) {
            mSkinHeadView.setImageBitmap(face);
        } else {
            mSkinHeadView.setImageResource(R.drawable.ic_default_steve_head);
        }
        mUsernameView.setText(active.username);
        if (active.authType == AuthType.MICROSOFT) {
            mBadgeView.setText(R.string.zyrox_badge_microsoft);
        } else if (active.authType == AuthType.ELY_BY) {
            mBadgeView.setText(R.string.zyrox_badge_elyby);
        } else {
            mBadgeView.setText(R.string.zyrox_badge_offline);
        }
    }

    private void confirmDeleteAccount(Context context, Account account) {
        new AlertDialog.Builder(context)
                .setMessage(R.string.warning_remove_account)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.global_delete, (d, w) -> {
                    Accounts.delete(account);
                    ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
                    loadSavedAccounts();
                })
                .show();
    }

    private void launchAuthFragment(Class<? extends Fragment> fragmentClass, String fragmentTag) {
        if (!ProgressLayout.ensureNoOngoingTasks(requireContext(), ProgressLayout.AUTHENTICATE)) return;
        Tools.swapFragment(requireActivity(), fragmentClass, fragmentTag, null);
    }

    private class VaultAccountAdapter extends RecyclerView.Adapter<VaultAccountAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_zyrox_vault_account, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Account account = mAccounts.get(position);
            holder.name.setText(account.username);
            Bitmap face = account.getSkinFace();
            if (face != null) {
                holder.face.setImageBitmap(face);
            } else {
                holder.face.setImageResource(R.drawable.ic_default_steve_face);
            }
            holder.itemView.setSelected(position == mSelectedIndex);
            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= mAccounts.size()) return;
                Account chosen = mAccounts.get(pos);
                Accounts.setCurrent(chosen);
                int prev = mSelectedIndex;
                mSelectedIndex = pos;
                if (prev >= 0) notifyItemChanged(prev);
                notifyItemChanged(mSelectedIndex);
                updatePreviewPanel();
                ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
            });
            holder.deleteBtn.setOnClickListener(v -> confirmDeleteAccount(v.getContext(), account));
        }

        @Override
        public int getItemCount() {
            return mAccounts.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView face;
            final TextView name;
            final ImageButton deleteBtn;

            VH(@NonNull View itemView) {
                super(itemView);
                face = itemView.findViewById(R.id.iv_vault_acc_face);
                name = itemView.findViewById(R.id.tv_vault_acc_name);
                deleteBtn = itemView.findViewById(R.id.btn_vault_acc_delete);
            }
        }
    }
}
