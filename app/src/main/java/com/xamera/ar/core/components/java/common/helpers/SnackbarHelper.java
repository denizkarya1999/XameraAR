package com.xamera.ar.core.components.java.common.helpers;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

public final class SnackbarHelper {
  // Updated: Background color is Maize (#FFCB05) with full opacity.
  private static final int BACKGROUND_COLOR = 0xFFFFCB05;
  private Snackbar messageSnackbar;
  private enum DismissBehavior { HIDE, SHOW, FINISH };
  private int maxLines = 2;
  private String lastMessage = "";
  private View snackbarView;

  public boolean isShowing() {
    return messageSnackbar != null;
  }

  /** Shows a snackbar with a given message. */
  public void showMessage(Activity activity, String message) {
    if (!message.isEmpty() && (!isShowing() || !lastMessage.equals(message))) {
      lastMessage = message;
      show(activity, message, DismissBehavior.HIDE);
    }
  }

  /** Shows a snackbar with a given message, and a dismiss button. */
  public void showMessageWithDismiss(Activity activity, String message) {
    show(activity, message, DismissBehavior.SHOW);
  }

  /** Shows a snackbar with a given message for Snackbar.LENGTH_SHORT milliseconds */
  public void showMessageForShortDuration(Activity activity, String message) {
    show(activity, message, DismissBehavior.SHOW, Snackbar.LENGTH_SHORT);
  }

  /** Shows a snackbar with a given message for Snackbar.LENGTH_LONG milliseconds */
  public void showMessageForLongDuration(Activity activity, String message) {
    show(activity, message, DismissBehavior.SHOW, Snackbar.LENGTH_LONG);
  }

  /**
   * Shows a snackbar with a given error message. When dismissed, will finish the activity.
   */
  public void showError(Activity activity, String errorMessage) {
    show(activity, errorMessage, DismissBehavior.FINISH);
  }

  /**
   * Hides the currently showing snackbar, if there is one.
   */
  public void hide(Activity activity) {
    if (!isShowing()) {
      return;
    }
    lastMessage = "";
    Snackbar messageSnackbarToHide = messageSnackbar;
    messageSnackbar = null;
    activity.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        messageSnackbarToHide.dismiss();
      }
    });
  }

  public void setMaxLines(int lines) {
    maxLines = lines;
  }

  /** Returns whether the snackbar is currently being shown with an indefinite duration. */
  public boolean isDurationIndefinite() {
    return isShowing() && messageSnackbar.getDuration() == Snackbar.LENGTH_INDEFINITE;
  }

  /**
   * Sets the view that will be used to find a suitable parent view to hold the Snackbar view.
   */
  public void setParentView(View snackbarView) {
    this.snackbarView = snackbarView;
  }

  private void show(Activity activity, String message, DismissBehavior dismissBehavior) {
    show(activity, message, dismissBehavior, Snackbar.LENGTH_INDEFINITE);
  }

  private void show(
          final Activity activity,
          final String message,
          final DismissBehavior dismissBehavior,
          int duration) {
    activity.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        messageSnackbar = Snackbar.make(
                snackbarView == null
                        ? activity.findViewById(android.R.id.content)
                        : snackbarView,
                message,
                duration);
        // Set the background color to Maize.
        messageSnackbar.getView().setBackgroundColor(BACKGROUND_COLOR);
        // Set the text color to UMich dark blue (#00274C).
        TextView snackbarText = messageSnackbar.getView()
                .findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarText.setMaxLines(maxLines);
        snackbarText.setTextColor(Color.parseColor("#00274C"));

        if (dismissBehavior != DismissBehavior.HIDE && duration == Snackbar.LENGTH_INDEFINITE) {
          messageSnackbar.setAction("Dismiss", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
              messageSnackbar.dismiss();
            }
          });
          if (dismissBehavior == DismissBehavior.FINISH) {
            messageSnackbar.addCallback(new BaseTransientBottomBar.BaseCallback<Snackbar>() {
              @Override
              public void onDismissed(Snackbar transientBottomBar, int event) {
                super.onDismissed(transientBottomBar, event);
                activity.finish();
              }
            });
          }
        }
        messageSnackbar.show();
      }
    });
  }
}