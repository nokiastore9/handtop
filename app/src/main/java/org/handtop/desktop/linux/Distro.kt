package org.handtop.desktop.linux

/** Linux distributions HandTop can install: official arm64 images from Docker Hub. */
enum class Distro(
    val id: String,
    /** Short name for window titles. */
    val shortName: String,
    val title: String,
    val repository: String,
    val tag: String,
    /** Approximate download size, for the installer UI. */
    val downloadMb: Int,
) {
    DEBIAN("debian", "Debian", "Debian 13 (trixie)", "library/debian", "trixie-slim", 30),
    UBUNTU("ubuntu", "Ubuntu", "Ubuntu 26.04 LTS", "library/ubuntu", "26.04", 41),
    ALPINE("alpine", "Alpine", "Alpine Linux", "library/alpine", "latest", 4),
    FEDORA("fedora", "Fedora", "Fedora", "library/fedora", "latest", 68);

    val image get() = "$repository:$tag"

    companion object {
        val DEFAULT = DEBIAN

        fun byId(id: String?): Distro? = entries.firstOrNull { it.id == id }
    }
}
