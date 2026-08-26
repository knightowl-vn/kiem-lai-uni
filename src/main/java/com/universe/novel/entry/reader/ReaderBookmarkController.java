package com.universe.novel.entry.reader;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.exceptions.BookmarkLimitExceededException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.BookmarkChapterCommand;
import com.universe.novel.application.reader.BookmarkChapterUseCase;
import com.universe.novel.application.reader.ListUserBookmarkedChaptersUseCase;
import com.universe.novel.application.reader.UnbookmarkChapterCommand;
import com.universe.novel.application.reader.UnbookmarkChapterUseCase;
import com.universe.novel.contracts.dto.reader.ReaderBookmarkedChapterDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequestMapping("/novel")
public class ReaderBookmarkController {

    private final BookmarkChapterUseCase bookmarkChapterUseCase;
    private final UnbookmarkChapterUseCase unbookmarkChapterUseCase;
    private final ListUserBookmarkedChaptersUseCase listUserBookmarkedChaptersUseCase;
    private final AuthenticatedEmailResolver authenticatedEmailResolver;
    private final UserIdentityContract userIdentityContract;

    public ReaderBookmarkController(
            BookmarkChapterUseCase bookmarkChapterUseCase,
            UnbookmarkChapterUseCase unbookmarkChapterUseCase,
            ListUserBookmarkedChaptersUseCase listUserBookmarkedChaptersUseCase,
            AuthenticatedEmailResolver authenticatedEmailResolver,
            UserIdentityContract userIdentityContract
    ) {
        this.bookmarkChapterUseCase = Objects.requireNonNull(
                bookmarkChapterUseCase,
                "BookmarkChapterUseCase không được để trống."
        );
        this.unbookmarkChapterUseCase = Objects.requireNonNull(
                unbookmarkChapterUseCase,
                "UnbookmarkChapterUseCase không được để trống."
        );
        this.listUserBookmarkedChaptersUseCase = Objects.requireNonNull(
                listUserBookmarkedChaptersUseCase,
                "ListUserBookmarkedChaptersUseCase không được để trống."
        );
        this.authenticatedEmailResolver = Objects.requireNonNull(
                authenticatedEmailResolver,
                "AuthenticatedEmailResolver không được để trống."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract không được để trống."
        );
    }

    @PostMapping("/chapters/{chapterId}/bookmark")
    @ResponseBody
    public ResponseEntity<Void> bookmarkChapter(
            @PathVariable UUID chapterId,
            Authentication authentication
    ) {
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<UserDTO> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            bookmarkChapterUseCase.execute(
                    new BookmarkChapterCommand(
                            userOpt.get().id(),
                            chapterId
                    )
            );
            return ResponseEntity.noContent().build();
        } catch (ChapterNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (BookmarkLimitExceededException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @DeleteMapping("/chapters/{chapterId}/bookmark")
    @ResponseBody
    public ResponseEntity<Void> unbookmarkChapter(
            @PathVariable UUID chapterId,
            Authentication authentication
    ) {
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<UserDTO> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        unbookmarkChapterUseCase.execute(
                new UnbookmarkChapterCommand(
                        userOpt.get().id(),
                        chapterId
                )
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bookmarks")
    public String bookmarksPage(
            Authentication authentication,
            Model model
    ) {
        Optional<UserDTO> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return "redirect:/login";
        }

        List<ReaderBookmarkedChapterDTO> bookmarks =
                listUserBookmarkedChaptersUseCase.execute(userOpt.get().id());

        model.addAttribute("bookmarks", bookmarks);
        model.addAttribute("pageTitle", "Dấu trang chương");

        return "novel/reader/bookmarks";
    }

    private Optional<UserDTO> resolveUser(Authentication authentication) {
        return authenticatedEmailResolver.resolve(authentication)
                .flatMap(userIdentityContract::findByEmail);
    }
}
