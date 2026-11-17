package git.artdeell.mojo.input.handleview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;

import git.artdeell.mojo.R;
import git.artdeell.mojo.input.buttons.ControlInterface;

/**
 * Layout floating around a Control Button, displaying contextual actions
 */
public class ActionRow extends LinearLayout {

    public static final int SIDE_LEFT = 0x0;
    public static final int SIDE_TOP = 0x1;
    public static final int SIDE_RIGHT = 0x2;
    public static final int SIDE_BOTTOM = 0x3;
    public static final int SIDE_AUTO = 0x4;

    public ActionRow(Context context) {
        super(context); init();
    }
    public ActionRow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); init();
    }

    public final ViewTreeObserver.OnPreDrawListener mFollowedViewListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if(mFollowedView == null || !mFollowedView.isShown()){
                hide();
                return true;
            }

            setNewPosition();
            return true;
        }
    };
    private final ActionButtonInterface[] actionButtons = new ActionButtonInterface[3];
    private View mFollowedView = null;
    private final int mSide = SIDE_AUTO;

    /** Add action buttons and configure them */
    private void init(){
        setTranslationZ(11);
        setVisibility(GONE);
        setOrientation(HORIZONTAL);
        setBackgroundResource(R.drawable.bg_zyrox_floating_pill);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(4f * density);
        int gap = Math.round(4f * density);
        int hPad = Math.round(10f * density);
        setPadding(pad, pad, pad, pad);
        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                getResources().getDimensionPixelOffset(R.dimen._36sdp)
        ));

        actionButtons[0] = new DeleteButton(getContext());
        actionButtons[1] = new CloneButton(getContext());
        actionButtons[2] = new AddSubButton(getContext());

        for (int i = 0; i < actionButtons.length; i++) {
            View view = (View) actionButtons[i];
            if (view instanceof android.widget.Button) {
                android.widget.Button btn = (android.widget.Button) view;
                btn.setBackgroundResource(i == 0 ? R.drawable.btn_zyrox_danger : R.drawable.bg_zyrox_nav_item);
                btn.setTextColor(i == 0 ? 0xFFFFE4E8 : 0xFFFAFCFF);
                btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f);
                btn.setAllCaps(true);
                btn.setSingleLine(true);
                btn.setPadding(hPad, 0, hPad, 0);
                btn.setStateListAnimator(null);
                btn.setElevation(0f);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1F);
            if (i > 0) lp.setMarginStart(gap);
            addView(view, lp);
        }

        setElevation(8f * density);
    }

    public void setFollowedButton(ControlInterface controlInterface){
        if(mFollowedView != null)
            mFollowedView.getViewTreeObserver().removeOnPreDrawListener(mFollowedViewListener);

        for(ActionButtonInterface buttonInterface: actionButtons){
            buttonInterface.setFollowedView(controlInterface);
            ((View)(buttonInterface)).setVisibility(buttonInterface.shouldBeVisible() ? VISIBLE : GONE);
        }

        setVisibility(VISIBLE);
        mFollowedView = (View) controlInterface;
        if(mFollowedView != null)
            mFollowedView.getViewTreeObserver().addOnPreDrawListener(mFollowedViewListener);
    }

    private float getXPosition(int side){
        if(side == SIDE_LEFT){
            return mFollowedView.getX() - getWidth();
        }else if(side == SIDE_RIGHT){
            return mFollowedView.getX() + mFollowedView.getWidth();
        }else{
            return mFollowedView.getX() + mFollowedView.getWidth()/2f - getWidth()/2f;
        }
    }

    private float getYPosition(int side){
        if(side == SIDE_TOP){
            return mFollowedView.getY() - getHeight();
        } else if(side == SIDE_BOTTOM){
            return mFollowedView.getY() + mFollowedView.getHeight();
        }else{
            return mFollowedView.getY() + mFollowedView.getHeight()/2f - getHeight()/2f;
        }
    }

    private void setNewPosition(){
        if(mFollowedView == null) return;
        int side = pickSide();

        setX(MathUtils.clamp(getXPosition(side), 0, ((ViewGroup) getParent()).getWidth() - getWidth()));
        setY(getYPosition(side));
    }

    private int pickSide(){
        if(mFollowedView == null) return mSide; //Value should not matter

        if(mSide != SIDE_AUTO) return mSide;
        //TODO improve the "algo"
        ViewGroup parent = ((ViewGroup) mFollowedView.getParent());
        if(parent == null) return mSide;//Value should not matter

        int side = SIDE_TOP;
        float futurePos = getYPosition(side);
        if(futurePos + getHeight() > (parent.getHeight() + getHeight()/2f)){
            side = SIDE_TOP;
        }else if (futurePos < -getHeight()/2f){
            side = SIDE_BOTTOM;
        }

        return side;
    }

    public void hide(){
        if(mFollowedView != null)
            mFollowedView.getViewTreeObserver().removeOnPreDrawListener(mFollowedViewListener);
        setVisibility(GONE);
    }
}
