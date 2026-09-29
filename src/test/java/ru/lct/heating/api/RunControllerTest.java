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
import ru.lct.heating.calculation.CalculationMode;
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
        when(runService.create(datasetId, "grid-forest", false, CalculationMode.TWO_D))
                .thenReturn(run);
        when(apiMapper.toRunResponse(run)).thenReturn(RunResponse.builder().build());

        mockMvc.perform(post("/api/v1/datasets/{datasetId}/runs", datasetId)
                        .param("algorithm", "grid-forest"))
                .andExpect(status().isAccepted());

        verify(runService).create(datasetId, "grid-forest", false, CalculationMode.TWO_D);
    }

    @Test
    void withoutAlgorithm_usesDefault() throws Exception {
        UUID datasetId = UUID.randomUUID();
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        when(runService.create(datasetId, null, false, CalculationMode.TWO_D)).thenReturn(run);
        when(apiMapper.toRunResponse(run)).thenReturn(RunResponse.builder().build());

        mockMvc.perform(post("/api/v1/datasets/{datasetId}/runs", datasetId))
                .andExpect(status().isAccepted());

        verify(runService).create(eq(datasetId), isNull(), eq(false), eq(CalculationMode.TWO_D));
    }

    @Test
    void passesTraceParameter() throws Exception {
        UUID datasetId = UUID.randomUUID();
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        when(runService.create(datasetId, "grid-forest", true, CalculationMode.TWO_D))
                .thenReturn(run);
        when(apiMapper.toRunResponse(run)).thenReturn(RunResponse.builder().build());

        mockMvc.perform(post("/api/v1/datasets/{datasetId}/runs", datasetId)
                        .param("algorithm", "grid-forest")
                        .param("trace", "true"))
                .andExpect(status().isAccepted());

        verify(runService).create(datasetId, "grid-forest", true, CalculationMode.TWO_D);
    }

    @Test
    void passesDepthModeParameter() throws Exception {
        UUID datasetId = UUID.randomUUID();
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        when(runService.create(datasetId, null, false, CalculationMode.DEPTH)).thenReturn(run);
        when(apiMapper.toRunResponse(run)).thenReturn(RunResponse.builder().build());

        mockMvc.perform(post("/api/v1/datasets/{datasetId}/runs", datasetId)
                        .param("mode", "depth"))
                .andExpect(status().isAccepted());

        verify(runService).create(datasetId, null, false, CalculationMode.DEPTH);
    }

    @Test
    void unknownModeRejected() throws Exception {
        UUID datasetId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/datasets/{datasetId}/runs", datasetId)
                        .param("mode", "3d"))
                .andExpect(status().isBadRequest());
    }
}
