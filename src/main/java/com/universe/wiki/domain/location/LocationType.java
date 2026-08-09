package com.universe.wiki.domain.location;

/**
 * Phân loại địa điểm nằm trong một thế giới.
 */
public enum LocationType {

    /**
     * Châu lục.
     */
    CONTINENT,

    /**
     * Quốc gia, vương triều hoặc đế quốc.
     */
    NATION,

    /**
     * Một vùng hoặc khu vực địa lý.
     */
    REGION,

    /**
     * Thành trì hoặc thành phố.
     */
    CITY,

    /**
     * Thị trấn hoặc thôn làng.
     */
    TOWN,

    /**
     * Núi hoặc dãy núi.
     */
    MOUNTAIN,

    /**
     * Sông, hồ hoặc vùng biển.
     */
    WATER_BODY,

    /**
     * Nơi đặt tổng bộ hoặc lãnh địa của thế lực.
     */
    FACTION_TERRITORY,

    /**
     * Chiến trường hoặc khu vực chiến đấu đặc biệt.
     */
    BATTLEFIELD,

    /**
     * Địa điểm khác chưa được phân loại.
     */
    OTHER
}