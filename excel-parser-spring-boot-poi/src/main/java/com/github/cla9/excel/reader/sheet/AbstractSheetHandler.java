package com.github.cla9.excel.reader.sheet;

import com.github.cla9.excel.reader.entity.ExcelMetaModel;
import com.github.cla9.excel.reader.exception.InvalidHeaderException;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.cla9.excel.reader.util.ExcelConstant.UNDECIDED;

public abstract class AbstractSheetHandler implements SheetHandler {
    protected final ExcelMetaModel excelMetaModel;
    protected List<String> headerNames;
    protected int[] order;

    protected AbstractSheetHandler(ExcelMetaModel metadata) {
        this.excelMetaModel = metadata;
        this.headerNames = new ArrayList<>();
    }


    protected void createOrder(){
        if(!excelMetaModel.isPartialParseOperation()){
            this.order = IntStream.range(0, headerNames.size()).toArray();
        } else if (excelMetaModel.hasAllColumnOrder()) {
            this.order = excelMetaModel.getOrder();
        } else {
            Map<String, List<Integer>> headerMap = getHeaderIndexedMap();

            final List<String> metadataHeaders = this.excelMetaModel.getHeaders();
            this.order = IntStream.range(0, metadataHeaders.size())
                    .map(i -> {
                        final String headerName = metadataHeaders.get(i);
                        final List<Integer> indices = headerMap.get(headerName);
                        if (Objects.isNull(indices))
                            throw new InvalidHeaderException("There is no matched headerName. (" + headerName +")");
                        if (indices.size() == 1) {
                            return indices.get(0);
                        } else {
                            final int columnOrder = excelMetaModel.getOrder()[i];
                            if (indices.size() > 1 && columnOrder != UNDECIDED) {
                                if(indices.indexOf(columnOrder) == -1)
                                    throw new InvalidHeaderException("There is no matched header Index. headerName : " + headerName + " index : " + columnOrder);
                                return columnOrder;
                            }
                        }
                        return -1;
                    })
                    .filter(i -> i != -1)
                    .toArray();
        }
    }
    protected void validateOrder(){
        final int[] metaDataOrder = excelMetaModel.getOrder();
        if(metaDataOrder.length != order.length) {
            throw new InvalidHeaderException("Actual file headerName and ExcelColumn information doesn't matched");
        }
        IntStream.range(0, metaDataOrder.length)
                .filter(i -> (order[i] != metaDataOrder[i] && metaDataOrder[i] != UNDECIDED) || order[i] >= headerNames.size())
                .forEach(i -> {
                    throw new InvalidHeaderException("Index doesn't matched with actual excel file column order. index : " + (metaDataOrder[i]+1));
                });
    }

    protected void validateHeader(){
        if (!excelMetaModel.hasAllColumnOrder() && excelMetaModel.getHeaders().size() != headerNames.size())
            throw new InvalidHeaderException("There is mismatched header name");
    }
    protected void reOrderHeaderName(){
        headerNames = Arrays.stream(order).mapToObj(item -> headerNames.get(item))
                .collect(Collectors.toList());
    }

    private Map<String, List<Integer>> getHeaderIndexedMap() {
        Map<String, List<Integer>> headerMap = new HashMap<>();
        for(int i = 0; i < headerNames.size(); i++){
            final String headerName = headerNames.get(i);
            List<Integer> indices = headerMap.get(headerName);
            if(Objects.isNull(indices)){
                indices = new ArrayList<>();
            }
            indices.add(i);
            headerMap.put(headerName, indices);
        }
        return headerMap;
    }

    @Override
    public List<String> getHeaderNames() {
        if (!excelMetaModel.hasAllColumnOrder() || !excelMetaModel.isPartialParseOperation()) {
            return headerNames;
        }
        else {
            return excelMetaModel.getHeaders();
        }
    }

    @Override
    public int[] getOrder() {
        return order.clone();
    }
}