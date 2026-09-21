package ru.lct.heating.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.lct.heating.routing.algorithm.AlgorithmInfo;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;

@WebMvcTest(AlgorithmController.class)
class AlgorithmControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TracingAlgorithmRegistry registry;

    @Test
    void listsAlgorithms() throws Exception {
        when(registry.available()).thenReturn(List.of(
                AlgorithmInfo.builder().id("grid-forest")
                        .description("Классический лес").defaultAlgorithm(true).build()));

        mockMvc.perform(get("/api/v1/algorithms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("grid-forest"))
                .andExpect(jsonPath("$[0].description").value("Классический лес"))
                .andExpect(jsonPath("$[0].defaultAlgorithm").value(true));
    }
}
