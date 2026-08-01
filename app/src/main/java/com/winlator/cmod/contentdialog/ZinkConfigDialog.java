package com.winlator.cmod.contentdialog;

import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.widget.MultiSelectionComboBox;

import java.util.HashMap;

/**
 * Zink's own knobs, which used to be typed into the container's environment as
 * ZINK_DESCRIPTORS / ZINK_DEBUG / ZINK_INLINE_UNIFORMS whether or not zink was
 * the driver in use. XServerDisplayActivity turns these back into those
 * variables, and only when zink is selected.
 */
public class ZinkConfigDialog extends ContentDialog {

    public ZinkConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.zink_config_dialog);

        setIcon(R.drawable.icon_settings);
        setTitle(anchor.getContext().getString(R.string.zink_configuration));

        HashMap<String, String> config = OpenglDriverConfig.parse(anchor.getTag().toString());

        Spinner sDescriptors = findViewById(R.id.SZinkDescriptors);
        MultiSelectionComboBox mscDebug = findViewById(R.id.MSCZinkDebug);
        CheckBox cbInlineUniforms = findViewById(R.id.CBZinkInlineUniforms);
        CheckBox cbShaderCache = findViewById(R.id.CBZinkShaderCache);

        AppUtils.setSpinnerSelectionFromValue(sDescriptors,
                OpenglDriverConfig.get(config, "descriptors", "auto"));

        /* No label, so the box shows the flags instead of a count; and the
         * plural setter, because setSelectedItem() only re-adds an item the
         * set already contains and so cannot restore anything.
         */
        mscDebug.setItems(anchor.getContext().getResources()
                .getStringArray(R.array.zink_debug_entries));
        mscDebug.setSelectedItems(OpenglDriverConfig.splitFlags(
                OpenglDriverConfig.get(config, "zinkDebug", "compact")));

        cbInlineUniforms.setChecked(OpenglDriverConfig.isEnabled(config, "inlineUniforms", false));
        cbShaderCache.setChecked(OpenglDriverConfig.isEnabled(config, "shaderCache", true));

        setOnConfirmCallback(() -> {
            /* Start from what is already stored so freedreno's half of the
             * string survives a trip through this dialog.
             */
            HashMap<String, String> updated = OpenglDriverConfig.parse(anchor.getTag().toString());

            updated.put("descriptors", sDescriptors.getSelectedItem().toString());
            updated.put("zinkDebug", mscDebug.getSelectedItemsAsString());
            updated.put("inlineUniforms", cbInlineUniforms.isChecked() ? "1" : "0");
            updated.put("shaderCache", cbShaderCache.isChecked() ? "1" : "0");

            anchor.setTag(OpenglDriverConfig.write(updated));
        });
    }
}
