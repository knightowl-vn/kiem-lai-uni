package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicNovelLandingCachePort;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetReaderNovelLandingUseCaseTest {

    @Mock
    private PublicNovelLandingCachePort
            cachePort;

    @Mock
    private PublicNovelLandingLoader
            loader;

    private GetReaderNovelLandingUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new GetReaderNovelLandingUseCase(
                        cachePort,
                        loader
                );
    }

    @Test
    @DisplayName(
            "Execute calls cache getOrLoad"
    )
    void shouldCallCacheGetOrLoad() {
        ReaderNovelLandingDTO expected = mock(ReaderNovelLandingDTO.class);
        when(cachePort.getOrLoad(any())).thenAnswer(inv -> {
            Supplier<ReaderNovelLandingDTO> supplier = inv.getArgument(0);
            return expected;
        });

        ReaderNovelLandingDTO result = useCase.execute();

        assertThat(result).isSameAs(expected);
        verify(cachePort).getOrLoad(any());
    }
}