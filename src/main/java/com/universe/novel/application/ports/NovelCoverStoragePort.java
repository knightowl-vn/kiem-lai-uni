package com.universe.novel.application.ports;

import com.universe.novel.application.profile.NovelCoverUpload;

public interface NovelCoverStoragePort {

    String upload(
            String novelSlug,
            NovelCoverUpload coverUpload
    );
}
