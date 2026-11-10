package cn.trial.videosearch.evidence;

/** Versioned native evidence limits; legacy assets keep their original bounds. */
enum NativeRecordingFormat {
    LEGACY("same-session-recording-v1","session-segment-input-v1",16*1024*1024,256*1024,20_000_000L,1800),
    CONTEXT("same-session-recording-v2","session-segment-input-v2",128*1024*1024,1024*1024,75_000_000L,7200);

    final String schema,inputVersion;
    final int maxBytes,maxManifestBytes,maxPackets;
    final long maxDurationUs;
    NativeRecordingFormat(String schema,String inputVersion,int maxBytes,int maxManifestBytes,long maxDurationUs,int maxPackets){
        this.schema=schema;this.inputVersion=inputVersion;this.maxBytes=maxBytes;this.maxManifestBytes=maxManifestBytes;this.maxDurationUs=maxDurationUs;this.maxPackets=maxPackets;
    }
    static NativeRecordingFormat manifest(Object schema){
        for(var format:values())if(format.schema.equals(schema))return format;
        throw new IllegalArgumentException("Unknown native recording schema");
    }
    static NativeRecordingFormat input(Object version){
        for(var format:values())if(format.inputVersion.equals(version))return format;
        throw new IllegalArgumentException("Unknown native recording input");
    }
}
