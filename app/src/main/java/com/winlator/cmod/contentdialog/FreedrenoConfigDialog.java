package com.winlator.cmod.contentdialog;

import android.view.View;
import android.widget.CheckBox;

import com.winlator.cmod.R;
import com.winlator.cmod.widget.MultiSelectionComboBox;

import java.util.HashMap;

public class FreedrenoConfigDialog extends ContentDialog {

    public FreedrenoConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.freedreno_config_dialog);

        setIcon(R.drawable.icon_settings);
        setTitle(anchor.getContext().getString(R.string.freedreno_configuration));

        HashMap<String, String> config = OpenglDriverConfig.parse(anchor.getTag().toString());

        MultiSelectionComboBox mscDebug = findViewById(R.id.MSCFreedrenoDebug);
        CheckBox cbVSync = findViewById(R.id.CBFreedrenoVSync);
        CheckBox cbShaderCache = findViewById(R.id.CBFreedrenoShaderCache);

        mscDebug.setItems(anchor.getContext().getResources()
                .getStringArray(R.array.freedreno_debug_entries));
        mscDebug.setSelectedItems(OpenglDriverConfig.splitFlags(
                OpenglDriverConfig.get(config, "fdDebug", "sysmem")));

        cbVSync.setChecked(OpenglDriverConfig.isEnabled(config, "vsync", false));
        cbShaderCache.setChecked(OpenglDriverConfig.isEnabled(config, "shaderCache", true));

        setOnConfirmCallback(() -> {
            HashMap<String, String> updated = OpenglDriverConfig.parse(anchor.getTag().toString());

            updated.put("fdDebug", mscDebug.getSelectedItemsAsString());
            updated.put("vsync", cbVSync.isChecked() ? "1" : "0");
            updated.put("shaderCache", cbShaderCache.isChecked() ? "1" : "0");

            anchor.setTag(OpenglDriverConfig.write(updated));
        });
    }
}
