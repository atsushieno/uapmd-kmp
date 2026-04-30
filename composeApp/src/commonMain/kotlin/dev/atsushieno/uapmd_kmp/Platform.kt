package dev.atsushieno.uapmd_kmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform