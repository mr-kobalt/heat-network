package ru.lct.heating.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.lct.heating.calculation.RunService;
import ru.lct.heating.persistence.CalculationRunEntity;

@WebMvcTest(RunController.class)
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RunService runService;

    @MockBean
    private ApiMapper apiMapper;

    @Test
    void passesAlgorithmParameter() throws Exception {
        UUID datasetId = UUID.randomUUID();
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        when(runService.create(datasetId, "grid-forest", false)).thenReturn(run);
        when(apiMapper.toRunResponse(run)).thenReturn(RunResponse.builder().build());

        mockMvc.perform(post("/api/v1/datasets/{datasetId}/runs", datasetId)
                        .param("algorithm", "grid-forest"))
                .andExpect(status().isAccepted());

        verify(runService).create(datasetId, "grid-forest", false);
    }

    @Test
    void withoutAlgorithm_usesDefault() throws Exception {
        UUID datasetId = UUID.randomUUID();
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        when(runService.create(datasetId, null, false)).thenReturn(run);
        when(apiMapper.toRunResponse(run)).thenReturn(RunResponse.builder().build());

        mockMvc.perform(post("/api/v1/datasets/{datasetId}/runs", datasetId))
                .andExpect(status().isAccepted());

        verify(runService).create(eq(datasetId), isNull(), eq(false));
    }

    @Test
    void passesTraceParameter() throws Exception {
        UUID datasetId = UUID.randomUUID();
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        when(runService.create(datasetId, "grid-forest", true)).thenReturn(run);
        when(apiMapper.toRunResponse(run)).thenReturn(RunResponse.builder().build());

        mockMvc.perform(post("/api/v1/datasets/{datasetId}/runs", datasetId)
                        .param("algorithm", "grid-forest")
                        .param("trace", "true"))
                .andExpect(status().isAccepted());

        verify(runService).create(datasetId, "grid-forest", true);
    }
}
