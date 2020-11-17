package com.github.cla9.excel.reader.worker;

import com.github.cla9.excel.reader.annotation.*;
import com.github.cla9.excel.reader.entity.ExcelMetaModel;
import com.github.cla9.excel.reader.exception.*;
import com.github.cla9.excel.reader.row.Range;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.cla9.excel.reader.util.ExcelConstant.UNDECIDED;

public class ExcelEntityParser implements EntityParser {
    private ExcelBody bodyMetadata;
    private Class<?> tClass;
    private Set<Class<?>> visited;
    private MessageConverter messageConverter;
    private final EntityInstantiatorSource instantiatorSource;
    private boolean hasMergedCell;
    private Range headerRow;
    private Range dataRow;
    private List<Field> declaredFields;
    private List<String> headerNames;
    private List<Integer> order;

    public ExcelEntityParser() {
        declaredFields = new ArrayList<>();
        instantiatorSource = new EntityInstantiatorSource();
        headerNames = new ArrayList<>();
        visited = new HashSet<>();
        hasMergedCell = false;
        order = new ArrayList<>();
    }

    @Override
    public void parse(Class<?> clazz) {
        resourceCleanUp();
        this.tClass = clazz;
        bodyMetadata = tClass.getAnnotation(ExcelBody.class);
        validateExcelBodyAnnotation();
        messageConverter = MessageConverter.builder().source(bodyMetadata.messageSource()).build();
        doParse();
    }

    public void resourceCleanUp() {
        bodyMetadata = null;
        tClass = null;
        headerRow = null;
        messageConverter = null;
        dataRow = null;
        order.clear();
        declaredFields.clear();
        headerNames.clear();
        visited.clear();
        hasMergedCell = false;
    }

    private void doParse() {
        visited.add(tClass);
        findAllFields(tClass);
        final int annotatedFieldHeight = extractHeaderNames();

        calcHeaderRange(annotatedFieldHeight);
        validateHeaderRange();
        calcDataRowRange();
        validateOverlappedRange();
        extractOrder();
        validateOrder();
        validateHeaderNames();
    }


    private void validateOrder() {
        final int[] tempOrder = getOrder();
        Arrays.sort(tempOrder);
        for (int i = 1; i < tempOrder.length; i++) {
            if (tempOrder[i - 1] == tempOrder[i] && tempOrder[i] != UNDECIDED) {
                throw new InvalidHeaderException("Excel column index must be unique");
            }
        }
    }

    private void validateHeaderNames() {
        final int headerLength = headerNames.size();
        if (headerLength == 0) return;

        final Map<String, Integer> indexMap = new HashMap<>();

        for (int i = 0; i < headerLength; i++) {
            final String headerName = headerNames.get(i);
            Integer prevIndex = indexMap.get(headerName);
            if (Objects.isNull(prevIndex)) {
                indexMap.put(headerName, i);
            } else if (order.get(i) == UNDECIDED || order.get(prevIndex) == UNDECIDED) {
                throw new InvalidHeaderException("The header name must be different or the index must be specified. headerName : " + headerName);
            }
        }
    }

    private void extractOrder() {
        if (order.size() == 0) {
            order = IntStream.range(0, declaredFields.size())
                    .map(i -> UNDECIDED)
                    .boxed()
                    .collect(Collectors.toList());
        }
    }

    private void findAllFields(final Class<?> tClass) {
        ReflectionUtils.doWithFields(tClass, field -> {
            final Class<?> clazz = field.getType();
            if(visited.contains(clazz)){
                throw new UnsatisfiedDependencyException( "Unsatisfied dependency expressed between class '" +
                        tClass.getName()+"' and '" + clazz.getName() + "'" );
            }
            if (ClassUtils.isPrimitiveOrWrapper(clazz) || String.class.isAssignableFrom(clazz)) {
                declaredFields.add(field);
            } else {
                visited.add(clazz);
                findAllFields(clazz);
                visited.remove(clazz);
            }
        });
    }

    private void validateExcelBodyAnnotation() {
        if (!tClass.isAnnotationPresent(ExcelBody.class))
            throw new NoExcelEntityFoundException();

        if(bodyMetadata.headerRowPos() != UNDECIDED && bodyMetadata.headerRowRange().length != 0)
            throw new DuplicationHeaderRowPosException();

        if(bodyMetadata.headerRowRange().length > 1)
            throw new InvalidHeaderException("Only one header range is available.");

        if(bodyMetadata.dataRowPos() != UNDECIDED && bodyMetadata.dataRowRange().length != 0)
            throw new DuplicationDataRowPosException();

        if(bodyMetadata.dataRowRange().length > 1)
            throw new InvalidHeaderException("Only one data range is available.");

        if(bodyMetadata.dataRowPos() == UNDECIDED && bodyMetadata.dataRowRange().length < 1)
            throw new InvalidHeaderException("Either dataRowPos or dataRowRange must be entered.");
    }

    private void calcHeaderRange(final int height) {
        final int headerRowPos = bodyMetadata.headerRowPos() - 1;
        if (headerRowPos > UNDECIDED) {
            headerRow = Range.builder().start(headerRowPos).end(headerRowPos + height - 1).build();
        }
        else if(bodyMetadata.headerRowRange().length > 0){
            final RowRange headerRowRange = bodyMetadata.headerRowRange()[0];
            headerRow = Range.builder().start(headerRowRange.start()-1).end(headerRowRange.end()-1).build();
        }
        else {
            final int dataRowPos = bodyMetadata.dataRowPos();
            if (bodyMetadata.dataRowRange().length > 0) {
                RowRange range = bodyMetadata.dataRowRange()[0];
                headerRow = Range.builder().start(range.start() - 1 - height).end(range.end() - 2).build();
            } else {
                headerRow = Range.builder().start(dataRowPos - 1 - height).end(dataRowPos - 2).build();
            }
        }
    }


    private void validateHeaderRange() {
        if(headerRow.getStart() < 0)
            throw new InvalidHeaderException("Header start position must be greater than 1. start : " + (headerRow.getStart() + 1) + " end : " + (headerRow.getEnd() + 1));
    }

    private void calcDataRowRange() {
        int dataRowPos = bodyMetadata.dataRowPos() - 1;
        if (dataRowPos > UNDECIDED) {
            dataRow = Range.builder().start(dataRowPos).end(UNDECIDED).build();
        } else if (tClass.isAnnotationPresent(RowRange.class)) {
            final RowRange rowRange = tClass.getAnnotation(RowRange.class);
            dataRow = Range.builder()
                    .start(rowRange.start() - 1)
                    .end(rowRange.end() - 1)
                    .build();
        }
        validateDataRowRange();
    }

    private void validateDataRowRange() {
        if (Objects.isNull(dataRow)) {
            throw new DataRowPosNotFoundException();
        }
        if (dataRow.getEnd() != UNDECIDED && dataRow.getStart() > dataRow.getEnd()) {
            throw new InvalidHeaderException("Row start position cannot be greater than end position start : " + (dataRow.getStart()+1) + " end : " + (dataRow.getEnd()+1));
        }
        if (bodyMetadata.dataRowPos() != UNDECIDED && tClass.isAnnotationPresent(RowRange.class)) {
            throw new DuplicationDataRowPosException();
        }
    }

    private void validateOverlappedRange() {
        final var headerStart = headerRow.getStart();
        final var headerEnd = headerRow.getEnd();
        final var dataStart = dataRow.getStart();
        final var dataEnd = dataRow.getEnd();
        if ((headerEnd <= dataStart && dataStart <= headerEnd) ||
                (headerStart <= dataEnd && dataEnd <= headerEnd) ||
                (dataStart <= headerStart && headerStart <= dataEnd) ||
                (dataStart <= headerEnd && headerEnd <= dataEnd)) {
            throw new InvalidHeaderException("Header and data range cannot be overlapped.");
        }
    }

    private int findHeaderName(final Class<?> tClass, final String parentName, ExcelColumnOverrides overrides,  final int height) {
        final String PREFIX = parentName != null ? parentName + "." : "";
        final AtomicInteger maxHeight = new AtomicInteger(height);

        ReflectionUtils.doWithFields(tClass, f -> {
            final ExcelColumnOverrides fieldOverrideAnnotation = f.getAnnotation(ExcelColumnOverrides.class);
            if (f.isAnnotationPresent(ExcelColumn.class)) {
                final ExcelColumn excelColumn = f.getAnnotation(ExcelColumn.class);
                final String headerName = excelColumn.headerName();
                Optional<ExcelColumnOverride> overrideColumn = Optional.empty();

                if (!Objects.isNull(overrides)) {
                    overrideColumn = Arrays.stream(overrides.value())
                            .filter(o -> o.column().headerName().equals(headerName) && o.column().index() == o.column().index())
                            .findAny();
                }

                if (overrideColumn.isPresent()) {
                    final ExcelColumnOverride column = overrideColumn.get();
                    headerNames.add(PREFIX + messageConverter.convertMessage(column.headerName()));
                    order.add(column.index() == UNDECIDED ? UNDECIDED : column.index() - 1);
                } else {
                    headerNames.add(PREFIX + messageConverter.convertMessage(headerName));
                    order.add(excelColumn.index() == UNDECIDED ? UNDECIDED : excelColumn.index() - 1);
                }

            } else if (f.isAnnotationPresent(Merge.class)) {
                final Merge merge = f.getAnnotation(Merge.class);
                hasMergedCell = true;
                maxHeight.set(Math.max(maxHeight.get(), findHeaderName(f.getType(), PREFIX + messageConverter.convertMessage(merge.headerName()), fieldOverrideAnnotation, height+1)));

            } else if (f.isAnnotationPresent(ExcelEmbedded.class)) {
                maxHeight.set(Math.max(maxHeight.get(), findHeaderName(f.getType(), parentName, fieldOverrideAnnotation, height)));
            }
        });

        return maxHeight.get();
    }

    private int extractHeaderNames() {
        final ExcelColumnOverrides overridesAnnotation = tClass.getAnnotation(ExcelColumnOverrides.class);
        return findHeaderName(tClass, null, overridesAnnotation,1);
    }

    private Range getHeaderRow() {
        return Range.of(headerRow);
    }

    private Range getDataRow() {
        return Range.of(dataRow);
    }

    private int[] getOrder() {
        return order.stream().mapToInt(Integer::intValue).toArray();
    }



    public ExcelMetaModel getEntityMetadata() {
        return ExcelMetaModel.builder()
                .headers(List.copyOf(headerNames))
                .hasOrder(order.stream().anyMatch(i -> i != UNDECIDED))
                .headerRange(getHeaderRow())
                .dataRange(getDataRow())
                .instantiatorSource(new EntityInstantiatorSource())
                .isPartialParseOperation(hasExcelColumn())
                .mergedHeader(hasMergedCell)
                .allColumnOrder(hasAllColumnOrder())
                .order(getOrder())
                .build();
    }

    private boolean hasExcelColumn() {
        return headerNames.size() > 0;
    }
    private boolean hasAllColumnOrder() {
        if (order.size() == 0) return false;
        for (int i : order) {
            if (i == UNDECIDED) {
                return false;
            }
        }
        return true;
    }
}