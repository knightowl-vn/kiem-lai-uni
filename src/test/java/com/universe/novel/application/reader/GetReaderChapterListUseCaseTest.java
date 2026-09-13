package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderChapterListCachePort;
import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetReaderChapterListUseCaseTest {

	private static final UUID VOLUME_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID CHAPTER_81_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final UUID CHAPTER_83_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock
	private PublicReaderChapterListCachePort cachePort;

	@Mock
	private ReaderChapterListLoader loader;

	private GetReaderChapterListUseCase useCase;

	@BeforeEach
	void setUp() {
		useCase = new GetReaderChapterListUseCase(cachePort, loader);
	}

	@Test
	@DisplayName("Lấy danh sách Published Chapter của Volume theo chapterNumber ASC thông qua cache")
	void shouldGetPublishedChapterList() {

		ReaderChapterListItemDTO chapter81 = new ReaderChapterListItemDTO(CHAPTER_81_ID, 81, "Chương 81",
				"quyen-1-chuong-81");

		ReaderChapterListItemDTO chapter83 = new ReaderChapterListItemDTO(CHAPTER_83_ID, 83, "Chương 83",
				"quyen-1-chuong-83");

		when(cachePort.getOrLoad(eq(VOLUME_ID), any())).thenAnswer(invocation -> {
			Supplier<List<ReaderChapterListItemDTO>> s = invocation.getArgument(1);
			return s.get();
		});

		when(loader.load(VOLUME_ID)).thenReturn(List.of(chapter81, chapter83));

		List<ReaderChapterListItemDTO> result = useCase.execute(VOLUME_ID);

		assertThat(result).containsExactly(chapter81, chapter83);

		assertThat(result.stream().map(ReaderChapterListItemDTO::chapterNumber)).containsExactly(81, 83);

		verify(cachePort).getOrLoad(eq(VOLUME_ID), any());

		verify(loader).load(VOLUME_ID);
	}

	@Test
	@DisplayName("Trả danh sách rỗng khi Volume không có Published Chapter")
	void shouldReturnEmptyList() {

		when(cachePort.getOrLoad(eq(VOLUME_ID), any())).thenAnswer(invocation -> {
			Supplier<List<ReaderChapterListItemDTO>> s = invocation.getArgument(1);
			return s.get();
		});

		when(loader.load(VOLUME_ID)).thenReturn(List.of());

		List<ReaderChapterListItemDTO> result = useCase.execute(VOLUME_ID);

		assertThat(result).isEmpty();

		verify(cachePort).getOrLoad(eq(VOLUME_ID), any());

		verify(loader).load(VOLUME_ID);
	}

	@Test
	@DisplayName("Từ chối Volume ID null")
	void shouldRejectNullVolumeId() {

		assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(NullPointerException.class)
				.hasMessage("Volume ID không được để trống.");

		verifyNoInteractions(cachePort);
		verifyNoInteractions(loader);
	}
}
