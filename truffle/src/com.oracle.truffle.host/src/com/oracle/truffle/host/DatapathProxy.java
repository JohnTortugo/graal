package com.oracle.truffle.host;

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
@SuppressWarnings("unused")
public final class DatapathProxy implements TruffleObject {
    private final Map<String, Object> fields;

    public DatapathProxy(Object... values) {
        this.fields = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            this.fields.put("field" + (i + 1), values[i]);
        }
    }

    @ExportMessage
    Object readMember(String member) throws UnsupportedMessageException, UnknownIdentifierException {
        return read(member);
    }

    @ExportMessage
    void writeMember(String member, Object value) throws UnsupportedMessageException {
        write(member, value);
    }

    @TruffleBoundary(allowInlining = true)
    public Object read(String member) {
        return this.fields.get(member);
    }

    @TruffleBoundary(allowInlining = true)
    public void write(String member, Object value) {
        this.fields.put(member, value);
    }

    @ExportMessage
    final Object getMembers(boolean includeInternal) {
        return this.fields.keySet();
    }

    @ExportMessage
    final boolean hasMembers() {
        return true;
    }

    @ExportMessage
    final boolean isMemberReadable(String member) {
        return true;
    }

    @ExportMessage
    final boolean isMemberModifiable(String member) {
        return true;
    }

    @ExportMessage
    final boolean isMemberInsertable(String member) {
        return true;
    }

    public static boolean isDatapathProxyGuestObject(HostLanguage language, Object value) {
        Object unwrapped = HostLanguage.unwrapIfScoped(language, value);
        return unwrapped instanceof DatapathProxy;
    }

    public static Object toDatapathProxyObject(HostLanguage language, Object value) {
        return HostLanguage.unwrapIfScoped(language, value);
    }
}