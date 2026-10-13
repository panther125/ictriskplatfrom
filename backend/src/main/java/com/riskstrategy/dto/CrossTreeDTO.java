package com.riskstrategy.dto;

import com.riskstrategy.entity.CrossTree;
import com.riskstrategy.entity.CrossTreeCell;
import com.riskstrategy.entity.CrossTreeDimension;
import lombok.Data;
import java.util.List;

@Data
public class CrossTreeDTO {
    private CrossTree tree;
    private List<CrossTreeDimension> dimensions;
    private String sliceName;
    private List<CrossTreeCell> cells;

    public CrossTreeDTO(CrossTree tree, List<CrossTreeDimension> dimensions, String sliceName, List<CrossTreeCell> cells) {
        this.tree = tree;
        this.dimensions = dimensions;
        this.sliceName = sliceName;
        this.cells = cells;
    }
}
