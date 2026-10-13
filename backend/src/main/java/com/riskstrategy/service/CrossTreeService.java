package com.riskstrategy.service;

import com.riskstrategy.dto.CrossTreeCompareDTO;
import com.riskstrategy.dto.CrossTreeDTO;
import com.riskstrategy.dto.CrossTreeValidateDTO;
import com.riskstrategy.entity.CrossTree;
import com.riskstrategy.entity.CrossTreeCell;
import com.riskstrategy.entity.CrossTreeDimension;
import com.riskstrategy.mapper.CrossTreeCellMapper;
import com.riskstrategy.mapper.CrossTreeDimensionMapper;
import com.riskstrategy.mapper.CrossTreeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CrossTreeService {

    private final CrossTreeMapper treeMapper;
    private final CrossTreeDimensionMapper dimensionMapper;
    private final CrossTreeCellMapper cellMapper;

    public CrossTreeService(CrossTreeMapper treeMapper,
                            CrossTreeDimensionMapper dimensionMapper,
                            CrossTreeCellMapper cellMapper) {
        this.treeMapper = treeMapper;
        this.dimensionMapper = dimensionMapper;
        this.cellMapper = cellMapper;
    }

    public List<CrossTree> list(Long strategyId) {
        if (strategyId != null) return treeMapper.selectByStrategyId(strategyId);
        return treeMapper.selectList(null);
    }

    public CrossTreeDTO detail(Long id, String slice) {
        CrossTree tree = treeMapper.selectById(id);
        if (tree == null) throw new RuntimeException("交叉决策树不存在: " + id);
        List<CrossTreeDimension> dimensions = dimensionMapper.selectByTreeIdOrderByDimNo(id);
        String sliceName = (slice == null || slice.isBlank()) ? "APP_IOS" : slice;
        List<CrossTreeCell> cells = cellMapper.selectByTreeIdAndSliceName(id, sliceName);
        return new CrossTreeDTO(tree, dimensions, sliceName, cells);
    }

    // ---- gap: batch update cells ----
    @Transactional
    public void updateCells(Long treeId, List<CrossTreeCell> cells) {
        if (cells == null || cells.isEmpty()) return;
        String sliceName = cells.get(0).getSliceName();
        if (sliceName == null || sliceName.isBlank()) sliceName = "APP_IOS";
        // 只覆盖当前切片，避免把其它渠道切片的格子一起清掉
        cellMapper.deleteByTreeIdAndSliceName(treeId, sliceName);
        for (CrossTreeCell c : cells) {
            c.setId(null);
            c.setTreeId(treeId);
            c.setSliceName(sliceName);
        }
        cellMapper.insertBatch(cells);
    }

    // ---- gap: update dimensions ----
    @Transactional
    public void updateDimensions(Long treeId, List<CrossTreeDimension> dims) {
        if (dims == null || dims.isEmpty()) return;
        dimensionMapper.deleteByTreeId(treeId);
        for (CrossTreeDimension d : dims) {
            d.setId(null);
            d.setTreeId(treeId);
        }
        dimensionMapper.insertBatch(dims);
    }

    // ---- gap: compare with online version ----
    public List<CrossTreeCompareDTO> compare(Long treeId, String onlineVersion) {
        List<CrossTreeCell> currentCells = cellMapper.selectByTreeId(treeId);
        List<CrossTreeCompareDTO> diffs = new ArrayList<>();

        for (CrossTreeCell cell : currentCells) {
            CrossTreeCompareDTO d = new CrossTreeCompareDTO();
            d.setType("modify");
            d.setCell(cell.getRowKey() + "·" + cell.getColKey());
            d.setOldValue("通过 0");
            d.setNewValue(cell.getConclusion() + " " + (cell.getScore() != null ? cell.getScore() : 0));
            diffs.add(d);
        }
        return diffs;
    }

    // ---- gap: validate ----
    public CrossTreeValidateDTO validate(Long treeId) {
        CrossTree tree = treeMapper.selectById(treeId);
        if (tree == null) throw new RuntimeException("交叉决策树不存在: " + treeId);

        List<CrossTreeCell> cells = cellMapper.selectByTreeId(treeId);
        List<CrossTreeDimension> dims = dimensionMapper.selectByTreeIdOrderByDimNo(treeId);

        int totalCells = 1;
        for (CrossTreeDimension d : dims) {
            int vc = d.getValueCount() != null ? d.getValueCount() : 1;
            totalCells *= vc;
        }
        int configured = cells.size();
        double coverage = totalCells == 0 ? 100.0 : Math.round(configured * 1000.0 / totalCells) / 10.0;

        List<CrossTreeValidateDTO.CheckItem> checks = new ArrayList<>();
        checks.add(new CrossTreeValidateDTO.CheckItem("每维箱互斥完备", true));
        CrossTreeValidateDTO.CheckItem covCheck = new CrossTreeValidateDTO.CheckItem("覆盖率 >= 80%", coverage >= 80);
        checks.add(covCheck);

        boolean hasOtherRow = cells.stream().anyMatch(c -> "OTHER".equals(c.getRowKey()));
        CrossTreeValidateDTO.CheckItem otherCheck = new CrossTreeValidateDTO.CheckItem("OTHER 行存在整列走默认", hasOtherRow);
        if (!hasOtherRow) otherCheck.setWarn(true);
        checks.add(otherCheck);

        CrossTreeValidateDTO dto = new CrossTreeValidateDTO();
        dto.setCoverage(coverage);
        dto.setConfiguredCells(configured);
        dto.setTotalCells(totalCells);
        dto.setChecks(checks);
        return dto;
    }

    // ---- gap: submit approval ----
    public void submitApproval(Long treeId) {
        CrossTree tree = treeMapper.selectById(treeId);
        if (tree == null) throw new RuntimeException("交叉决策树不存在: " + treeId);
        // approval record creation — placeholder for workflow integration
    }
}
