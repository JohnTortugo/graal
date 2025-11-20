package com.oracle.truffle.host;

import java.util.HashMap;

import java.util.Map;
import java.util.function.Function;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class DatapathProxy implements TruffleObject {
    private final Object term;
    private final String ionType;
    private final Map<String, Object> fields;
    private final Function<String, Object> computeIfAbsent;

    public DatapathProxy(Object term, Map<String, Object> fields, Function<String, Object> computeIfAbsent, String ionType) {
        this.term = term;
        this.fields = fields;
        this.computeIfAbsent = computeIfAbsent;
        this.ionType = ionType;
    }

    @ExportMessage
    Object readMember(String member) {
        return read(member);
    }

    @ExportMessage
    void writeMember(String member, Object value) {
        write(member, value);
    }

    @TruffleBoundary(allowInlining = true)
    public Object read(String member) {
        return this.fields.computeIfAbsent(member, this.computeIfAbsent);
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
    boolean isMemberModifiable(String member) {
        return false;
    }

    @ExportMessage
    boolean isMemberInsertable(String member) {
        return false;
    }

    public static boolean isDatapathProxyGuestObject(HostLanguage language, Object value) {
        Object unwrapped = HostLanguage.unwrapIfScoped(language, value);
        return unwrapped instanceof DatapathProxy;
    }

    public static Object toDatapathProxyObject(HostLanguage language, Object value) {
        return HostLanguage.unwrapIfScoped(language, value);
    }

    public Object getTerm() {
        return this.term;
    }

    public String getIonType() {
        return this.ionType;
    }

    public boolean ionEquals(Object o) {
        return o != null;
    }
}