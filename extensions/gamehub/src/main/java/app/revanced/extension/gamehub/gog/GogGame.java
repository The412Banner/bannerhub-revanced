package app.revanced.extension.gamehub.gog;

/** Simple data class holding one GOG game's metadata. */
public class GogGame {
    public final String gameId;
    public final String title;
    public final String imageUrl;
    public final String description;
    public final String developer;
    public final String category;
    public final int generation; // 1 or 2 (0 = unknown)
    /** GOG's 2:3 box art (gamesdb vertical_cover) when known; null otherwise. */
    public final String verticalCover;

    public GogGame(String gameId, String title, String imageUrl,
                   String description, String developer, String category, int generation) {
        this(gameId, title, imageUrl, description, developer, category, generation, null);
    }

    public GogGame(String gameId, String title, String imageUrl,
                   String description, String developer, String category, int generation,
                   String verticalCover) {
        this.gameId     = gameId;
        this.title      = title;
        this.imageUrl   = imageUrl;
        this.description = description;
        this.developer  = developer;
        this.category   = category;
        this.generation = generation;
        this.verticalCover = (verticalCover == null || verticalCover.isEmpty()) ? null : verticalCover;
    }
}
