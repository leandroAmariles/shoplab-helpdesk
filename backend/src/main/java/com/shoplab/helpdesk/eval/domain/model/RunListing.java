package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.util.List;

/**
 * The runs of a kind, oldest first, with the counts the screen needs to say how many it shows and how many a filter
 * hides.
 *
 * @param runs           the most recent runs that match the filters, in chronological order
 * @param total          the runs of the kind that match the filters
 * @param totalAllOrigins the runs of the kind, whatever their origin
 * @param origins        the runs of the kind counted by origin
 */
public record RunListing(RunKind kind, List<RunListItem> runs, long total, long totalAllOrigins,
        List<OriginCount> origins) {
}
