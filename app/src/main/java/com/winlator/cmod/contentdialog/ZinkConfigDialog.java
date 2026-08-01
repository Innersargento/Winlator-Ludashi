package com.winlator.cmod.contentdialog;

import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.widget.MultiSelectionComboBox;

import java.util.HashMap;

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

        mscDebug.setItems(anchor.getContext().getResources()
                .getStringArray(R.array.zink_debug_entries));
        mscDebug.setSelectedItems(OpenglDriverConfig.splitFlags(
                OpenglDriverConfig.get(config, "zinkDebug", "compact")));

        cbInlineUniforms.setChecked(OpenglDriverConfig.isEnabled(config, "inlineUniforms", false));
        cbShaderCache.setChecked(OpenglDriverConfig.isEnabled(config, "shaderCache", true));

        setOnConfirmCallback(() -> {
            HashMap<String, String> updated = OpenglDriverConfig.parse(anchor.getTag().toString());

            updated.put("descriptors", sDescriptors.getSelectedItem().toString());
            updated.put("zinkDebug", mscDebug.getSelectedItemsAsString());
            updated.put("inlineUniforms", cbInlineUniforms.isChecked() ? "1" : "0");
            updated.put("shaderCache", cbShaderCache.isChecked() ? "1" : "0");

            anchor.setTag(OpenglDriverConfig.write(updated));
        });
    }
}
