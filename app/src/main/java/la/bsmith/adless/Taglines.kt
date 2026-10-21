package la.bsmith.adless

/** One line under the focused favorite's name on the Home tab, by package. */
object Taglines {
    private val byPackage = mapOf(
        "com.netflix.ninja" to "Movies, series and more",
        "com.hulu.plus" to "Live and on-demand TV",
        "com.plexapp.android" to "Your own library, anywhere",
        "com.disney.disneyplus" to "Disney, Pixar, Marvel, Star Wars",
        "com.hbo.hbonow" to "HBO originals and Warner Bros.",
        "com.peacock.peacockfiretv" to "NBC, live sports and originals",
        "com.amazon.firetv.youtube" to "Videos, creators and music",
        "com.amazon.firetv.youtube.tv" to "Live TV with cloud DVR",
        "com.apple.atve.amazon.appletv" to "Apple Originals",
        "com.amazon.firebat" to "Included with Prime",
        "com.fox.foxone" to "News, sports and entertainment",
        "com.amazon.bueller.music" to "Music, podcasts and playlists",
        "com.amazon.spiderpork" to "Cloud gaming",
        "com.amazon.cloud9" to "The web on your TV",
    )

    fun of(packageName: String): String? = byPackage[packageName]
}
