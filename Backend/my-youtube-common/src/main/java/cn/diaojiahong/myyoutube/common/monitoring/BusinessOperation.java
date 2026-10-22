package cn.diaojiahong.myyoutube.common.monitoring;

public enum BusinessOperation {
    REGISTER("identity"), LOGIN("identity"), REFRESH("identity"), LOGOUT("identity"), RESET_PASSWORD("identity"),
    PRESIGN_UPLOAD("upload"), SAVE_DYNAMIC("upload"), SAVE_METADATA("upload"), SUBMIT_CONTENT("upload"), RETRY_PREPARATION("upload"),
    CONTENT_DETAIL("consumption"), SUBTITLES("consumption"), SAVE_PLAYBACK("consumption"), CREATE_DOWNLOAD("consumption"),
    LIKE_CONTENT("interaction"), UNLIKE_CONTENT("interaction"), DISLIKE_CONTENT("interaction"),
    LIKE_COMMENT("interaction"), UNLIKE_COMMENT("interaction"), COLLECT("interaction"), UNCOLLECT("interaction"),
    COMMENT("interaction"), DELETE_COMMENT("interaction"), REPORT_COMMENT("interaction"), FOLLOW("interaction"), UNFOLLOW("interaction"),
    SEARCH_CONTENT("discovery", true), SEARCH_USER("discovery", true), SEARCH_TAG("discovery", true),
    HOT_SEARCH("discovery", true), HOT_SEARCH_HISTORY("discovery", true),
    RECOMMEND("discovery", true), RECOMMEND_FOLLOW("discovery", true),
    SEND_MESSAGE("messaging"), WITHDRAW_MESSAGE("messaging"), READ_MESSAGE("messaging"), CREATE_CONVERSATION("messaging"),
    MANUAL_REVIEW("publication"), BAN_CONTENT("publication"),
    EXPIRE_DRAFTS("maintenance"), CLEAN_DRAFTS("maintenance"), RECLAIM_MEDIA("maintenance"), RECOVER_PUBLICATION("maintenance");

    private final String chain;
    private final boolean resultList;

    BusinessOperation(String chain) { this(chain, false); }
    BusinessOperation(String chain, boolean resultList) { this.chain = chain; this.resultList = resultList; }
    public String chain() { return chain; }
    public boolean resultList() { return resultList; }
}
