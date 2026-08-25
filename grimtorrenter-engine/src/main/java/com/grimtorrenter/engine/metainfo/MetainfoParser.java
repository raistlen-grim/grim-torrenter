package com.grimtorrenter.engine.metainfo;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeDecoder;
import com.grimtorrenter.engine.bencode.BencodeEncoder;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses raw .torrent file bytes into a TorrentMetadata. The info-hash is
 * computed by re-encoding the parsed "info" dictionary (canonical, thanks
 * to BDictionary's sorted iteration) rather than slicing original source
 * bytes - see design_docs/0011 and 0012 for why that's reliable here.
 */
public final class MetainfoParser {

    private MetainfoParser() {
    }

    public static TorrentMetadata parse(byte[] torrentFileBytes) {
        BValue root = BencodeDecoder.decode(torrentFileBytes);
        if (!(root instanceof BDictionary top)) {
            throw new MetainfoException("Top-level bencode value is not a dictionary");
        }

        BValue infoValue = top.get("info");
        if (!(infoValue instanceof BDictionary info)) {
            throw new MetainfoException("Missing or invalid 'info' dictionary");
        }

        InfoHash infoHash = InfoHash.of(sha1(BencodeEncoder.encode(info)));

        String name = requireString(info, "name");
        long pieceLength = requireLong(info, "piece length");
        PieceHashes pieces = new PieceHashes(requireBytes(info, "pieces"));

        String announce = optionalString(top, "announce");
        List<List<String>> announceList = parseAnnounceList(top);

        BValue filesValue = info.get("files");
        if (filesValue == null) {
            long length = requireLong(info, "length");
            return new SingleFileTorrent(name, length, pieceLength, pieces, infoHash, announce, announceList);
        }

        List<TorrentFile> files = parseFiles(filesValue);
        return new MultiFileTorrent(name, files, pieceLength, pieces, infoHash, announce, announceList);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available on this JVM", e);
        }
    }

    private static String requireString(BDictionary dict, String key) {
        return requireBString(dict, key).utf8();
    }

    private static long requireLong(BDictionary dict, String key) {
        BValue value = dict.get(key);
        if (!(value instanceof BInteger i)) {
            throw new MetainfoException("Missing or invalid required field '" + key + "'");
        }
        return i.value();
    }

    private static byte[] requireBytes(BDictionary dict, String key) {
        return requireBString(dict, key).bytes();
    }

    private static BString requireBString(BDictionary dict, String key) {
        BValue value = dict.get(key);
        if (!(value instanceof BString s)) {
            throw new MetainfoException("Missing or invalid required field '" + key + "'");
        }
        return s;
    }

    private static String optionalString(BDictionary dict, String key) {
        BValue value = dict.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof BString s)) {
            throw new MetainfoException("Field '" + key + "' expected to be a string");
        }
        return s.utf8();
    }

    private static List<List<String>> parseAnnounceList(BDictionary top) {
        BValue value = top.get("announce-list");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof BList tiers)) {
            throw new MetainfoException("Field 'announce-list' expected to be a list");
        }
        List<List<String>> result = new ArrayList<>();
        for (BValue tierValue : tiers.values()) {
            if (!(tierValue instanceof BList tier)) {
                throw new MetainfoException("Each announce-list tier must be a list");
            }
            List<String> urls = new ArrayList<>();
            for (BValue urlValue : tier.values()) {
                if (!(urlValue instanceof BString url)) {
                    throw new MetainfoException("Tracker URL must be a string");
                }
                urls.add(url.utf8());
            }
            result.add(List.copyOf(urls));
        }
        return List.copyOf(result);
    }

    private static List<TorrentFile> parseFiles(BValue filesValue) {
        if (!(filesValue instanceof BList list)) {
            throw new MetainfoException("Field 'files' expected to be a list");
        }
        List<TorrentFile> files = new ArrayList<>();
        for (BValue entryValue : list.values()) {
            if (!(entryValue instanceof BDictionary entry)) {
                throw new MetainfoException("Each file entry must be a dictionary");
            }
            long length = requireLong(entry, "length");
            BValue pathValue = entry.get("path");
            if (!(pathValue instanceof BList pathList)) {
                throw new MetainfoException("File entry missing 'path' list");
            }
            List<String> segments = new ArrayList<>();
            for (BValue segment : pathList.values()) {
                if (!(segment instanceof BString s)) {
                    throw new MetainfoException("Path segment must be a string");
                }
                segments.add(s.utf8());
            }
            files.add(new TorrentFile(List.copyOf(segments), length));
        }
        return List.copyOf(files);
    }
}
