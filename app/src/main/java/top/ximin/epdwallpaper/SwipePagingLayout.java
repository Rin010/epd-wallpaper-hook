package top.ximin.epdwallpaper;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

final class SwipePagingLayout extends FrameLayout {
    interface Listener {
        void onSwipeLeft();
        void onSwipeRight();
    }

    private final int threshold;
    private float downX;
    private float downY;
    private boolean paging;
    private Listener listener;

    SwipePagingLayout(Context context) {
        super(context);
        threshold = ViewConfiguration.get(context).getScaledTouchSlop() * 4;
        setClickable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                paging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getX() - downX;
                float deltaY = event.getY() - downY;
                if (Math.abs(deltaX) >= threshold
                        && Math.abs(deltaX) > Math.abs(deltaY) * 1.25f) {
                    paging = true;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float deltaX = event.getX() - downX;
            float deltaY = event.getY() - downY;
            if (paging && Math.abs(deltaX) >= threshold
                    && Math.abs(deltaX) > Math.abs(deltaY) * 1.25f
                    && listener != null) {
                if (deltaX < 0) {
                    listener.onSwipeLeft();
                } else {
                    listener.onSwipeRight();
                }
            }
            paging = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            paging = false;
        }
        return true;
    }
}
