package io.tvos;

/** Caption formatting follows the selected app language without changing the provider data. */
final class WatchingText {
    private WatchingText() {}

    static String caption(Recs.Pick pick) {
        String episode = "";
        if ("tv".equals(pick.kind) && !pick.watchingSeason.isEmpty() && !pick.watchingEpisode.isEmpty()) {
            episode = String.format(Ui.locale(), AppStrings.get(R.string.home_watching_episode),
                    pick.watchingSeason, pick.watchingEpisode);
        }
        if (pick.watchingRemaining < 0) {
            String service = WatchingSources.knownLabel(WatchingSources.id(pick.pkg));
            if (service.isEmpty()) service = pick.pkg == null ? "" : pick.pkg;
            return episode.isEmpty() ? service : service.isEmpty() ? episode : episode + " · " + service;
        }
        String remaining = String.format(Ui.locale(), AppStrings.get(R.string.home_watching_remaining),
                pick.watchingRemaining);
        return episode.isEmpty() ? remaining : episode + " · " + remaining;
    }
}
