package com.riskstrategy.dto;

import com.riskstrategy.entity.DataSourceView;
import com.riskstrategy.entity.ViewField;
import com.riskstrategy.entity.ViewInputVar;
import lombok.Data;
import java.util.List;

@Data
public class ViewDetailDTO {
    private DataSourceView view;
    private List<ViewField> fields;
    private List<ViewInputVar> inputs;

    public ViewDetailDTO(DataSourceView view, List<ViewField> fields, List<ViewInputVar> inputs) {
        this.view = view;
        this.fields = fields;
        this.inputs = inputs;
    }
}
