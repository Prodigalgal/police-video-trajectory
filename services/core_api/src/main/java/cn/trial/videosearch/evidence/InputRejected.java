package cn.trial.videosearch.evidence;

public final class InputRejected extends RuntimeException {
    public final int status;
    public final String code;
    public InputRejected(int status,String code){super(code);this.status=status;this.code=code;}
}
