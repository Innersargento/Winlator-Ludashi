package com.winlator.cmod.contentdialog;

import android.view.View;
import android.widget.CheckBox;

import com.winlator.cmod.R;
import com.winlator.cmod.widget.MultiSelectionComboBox;

import java.util.HashMap;

/**
 * Freedreno's knobs. FD_MESA_DEBUG is the whole of the driver's tuning surface
 * -- there is no equivalent of ZINK_DESCRIPTORS -- so the flag list carries
 * most of the weight here. MESA_DEBUG is separate because it is core Mesa, not
 * freedreno, and it is what makes warnings such as the MakeCurrent visual
 * mismatch print at all.
 *
 * mesa_glthread is deliberately absent: _mesa_glthread_init() returns early
 * unless the driver reports map_unsynchronized_thread_safe, which freedreno
 * does not, so the toggle would control nothing.
 */
public class FreedrenoConfigDialog extends ContentDialog {

    public FreedrenoConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.freedreno_config_dialog);

        setIcon(R.drawable.icon_settings);
        setTitle(anchor.getContext().getString(R.string.freedreno_configuration));

        HashMap<String, String> config = OpenglDriverConfig.parse(anchor.getTag().toString());

        MultiSelectionComboBox mscDebug = findViewById(R.id.MSCFreedrenoDebug);
        CheckBox cbMesaDebug = findViewById(R.id.CBFreedrenoMesaDebug);
        CheckBox cbShaderCache = findViewById(R.id.CBFreedrenoShaderCache);

        /* setItems() without a label, so the box shows the flags themselves
         * rather than "N Freedreno Debug" -- the point of these is knowing at a
         * glance what is switched on. And setSelectedItems(), not repeated
         * setSelectedItem(): the singular only adds an item the set already
         * holds, so restoring a saved selection with it is a no-op.
         */
        mscDebug.setItems(anchor.getContext().getResources()
                .getStringArray(R.array.freedreno_debug_entries));
        mscDebug.setSelectedItems(OpenglDriverConfig.splitFlags(
                OpenglDriverConfig.get(config, "fdDebug", "")));

        cbMesaDebug.setChecked(OpenglDriverConfig.isEnabled(config, "mesaDebug", false));
        cbShaderCache.setChecked(OpenglDriverConfig.isEnabled(config, "shaderCache", true));

        setOnConfirmCallback(() -> {
            /* Start from what is already stored so zink's half of the string
             * survives a trip through this dialog.
             */
            HashMap<String, String> updated = OpenglDriverConfig.parse(anchor.getTag().toString());

            updated.put("fdDebug", mscDebug.getSelectedItemsAsString());
            updated.put("mesaDebug", cbMesaDebug.isChecked() ? "1" : "0");
            updated.put("shaderCache", cbShaderCache.isChecked() ? "1" : "0");

            anchor.setTag(OpenglDriverConfig.write(updated));
        });
    }
}
