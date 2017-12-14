package com.zeapo.pwdstore.autofill;

import android.app.assist.AssistStructure;
import android.content.Context;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Log;

@RequiresApi(api = Build.VERSION_CODES.M)
final class OreoAssistStructureParser {
    private final Context context;
    private final AssistStructure structure;

    public OreoAssistStructureParser(Context context, AssistStructure structure) {
        this.context = context;
        this.structure = structure;
    }

    public void parse() {
        Log.d(OreoAutofillService.Constants.TAG, "parse(): called");

        int numNodes = structure.getWindowNodeCount();
        for (int i = 0; i < numNodes; numNodes++) {
            final AssistStructure.WindowNode node = structure.getWindowNodeAt(i);
            final AssistStructure.ViewNode view = node.getRootViewNode();
        }
    }
}
