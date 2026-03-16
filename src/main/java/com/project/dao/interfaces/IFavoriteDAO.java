package com.project.dao.interfaces;

import com.project.model.favorites.Favorite;
import java.util.List;
import java.util.UUID;

public interface IFavoriteDAO {

    boolean setFavorite(UUID contentId, UUID userId, boolean isFavorite);

    List<Favorite> getFavoritesByUser(UUID userId);

    List<Favorite> getFavoritesByType(UUID userId, String type);
}
