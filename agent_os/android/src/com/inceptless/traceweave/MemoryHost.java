package com.inceptless.traceweave;

/** What the memory detail dialogs need from whichever screen owns them. */
interface MemoryHost {
    AgentStore storeRef();

    boolean english();

    int dpRef(int value);

    void toast(String message);

    void failWith(Exception error);

    void refreshAfterSync();
}
