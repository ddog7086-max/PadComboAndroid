package com.example.padcombo.core

/** 六色珠子：火 F / 水 W / 木 G / 光 L / 暗 D / 心 H */
enum class Orb(val code: Char) {
    FIRE('F'),
    WATER('W'),
    WOOD('G'),
    LIGHT('L'),
    DARK('D'),
    HEART('H');

    companion object {
        val ALL: List<Orb> = entries.toList()
    }
}
