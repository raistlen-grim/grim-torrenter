package com.grimtorrenter.engine.bencode;

public sealed interface BValue permits BString, BInteger, BList, BDictionary {
}
