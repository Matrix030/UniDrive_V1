package edu.nyu.unidrive.client.net;

import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface FeedbackApiClient {

    List<FeedbackSummaryResponse> listFeedback(String studentId) throws IOException;

    DownloadedFile downloadFeedback(String feedbackId) throws IOException;

    FeedbackSummaryResponse uploadFeedback(String submissionId, Path file) throws IOException;

    void deleteFeedback(String feedbackId) throws IOException;
}
