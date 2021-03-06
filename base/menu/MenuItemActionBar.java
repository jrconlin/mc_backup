/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.menu;

import org.mozilla.gecko.R;
import org.mozilla.gecko.util.DrawableUtil;
import org.mozilla.gecko.widget.ThemedImageButton;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

public class MenuItemActionBar extends ThemedImageButton
                               implements GeckoMenuItem.Layout {
    private static final String LOGTAG = "GeckoMenuItemActionBar";

    private final ColorStateList drawableColors;

    public MenuItemActionBar(Context context) {
        this(context, null);
    }

    public MenuItemActionBar(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.menuItemActionBarStyle);
    }

    public MenuItemActionBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.MenuItemActionBar, defStyle, 0);
        drawableColors = ta.getColorStateList(R.styleable.MenuItemActionBar_drawableTintList);
        ta.recycle();
    }

    @Override
    public void initialize(GeckoMenuItem item) {
        if (item == null)
            return;

        setIcon(item.getIcon());
        setTitle(item.getTitle());
        setEnabled(item.isEnabled());
        setId(item.getItemId());
    }

    void setIcon(Drawable icon) {
        if (icon == null) {
            setVisibility(GONE);
        } else {
            setVisibility(VISIBLE);
            DrawableUtil.tintDrawableWithStateList(icon, drawableColors);
            setImageDrawable(icon);
        }
    }

    void setIcon(int icon) {
        setIcon((icon == 0) ? null : getResources().getDrawable(icon));
    }

    void setTitle(CharSequence title) {
        // set accessibility contentDescription here
        setContentDescription(title);
    }

    @Override
    public void setShowIcon(boolean show) {
        // Do nothing.
    }
}
