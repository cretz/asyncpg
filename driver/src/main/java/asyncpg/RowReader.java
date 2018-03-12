package asyncpg;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.*;

public class RowReader {
  public static final Map<String, Converters.To> DEFAULT_CONVERTERS =
      Collections.unmodifiableMap(Converters.loadAllToConverters());
  public static final RowReader DEFAULT = new RowReader(DEFAULT_CONVERTERS, false);

  protected final Map<String, Converters.To> converters;

  public RowReader(Map<String, Converters.To> converterOverrides) {
    this(converterOverrides, true);
  }

  public RowReader(Map<String, Converters.To> converters, boolean prependDefaults) {
    Map<String, Converters.To> map;
    if (prependDefaults) {
      map = new HashMap<>(DEFAULT_CONVERTERS.size() + converters.size());
      map.putAll(DEFAULT_CONVERTERS);
    } else {
      map = new HashMap<>(converters.size());
    }
    map.putAll(converters);
    this.converters = Collections.unmodifiableMap(map);
  }

  public <@Nullable T> T get(QueryMessage.Row row, String colName, Class<T> typ) {
    if (row.meta == null) throw new DriverException.MissingRowMeta();
    QueryMessage.RowMeta.Column col = row.meta.columnsByName.get(colName.toLowerCase());
    if (col == null) throw new DriverException.ColumnNotPresent("No column for name " + colName);
    return get(col, row.raw[col.index], typ);
  }

  public <@Nullable T> T get(QueryMessage.Row row, int colIndex, Class<T> typ) {
    if (colIndex < 0 || colIndex > row.raw.length)
      throw new DriverException.ColumnNotPresent("No column at index " + colIndex);
    // No meta data means we use the unspecified type
    QueryMessage.RowMeta.Column col;
    if (row.meta != null) col = row.meta.columns[colIndex];
    else col = new QueryMessage.RowMeta.Column(colIndex, "", 0, (short) 0, DataType.UNSPECIFIED, (short) 0, 0, true);
    return get(col, row.raw[colIndex], typ);
  }

  @SuppressWarnings("unchecked")
  protected <@Nullable T> Converters.@Nullable To<? extends T> getConverter(Class<T> typ) {
    Converters.To conv = converters.get(typ.getName());
    if (conv != null || typ.getSuperclass() == null) return conv;
    return (Converters.To<? extends T>) getConverter(typ.getSuperclass());
  }

  @SuppressWarnings("unchecked")
  public <@Nullable T> T get(QueryMessage.RowMeta.Column col, byte@Nullable [] bytes, Class<T> typ) {
    Converters.To<? extends T> conv = getConverter(typ);
    if (conv == null) {
      // Handle as an array if necessary
      if (typ.isArray()) {
        if (bytes == null) return null;
        try {
          return (T) getArray(col, bytes, (Class<@Nullable ?>) typ.getComponentType());
        } catch (Exception e) { throw new DriverException.ConvertToFailed(typ, col.dataTypeOid, e); }
      }
      throw new DriverException.NoConversion(typ);
    }
    T ret;
    try {
      ret = (T) conv.convertToNullable(col, bytes);
    } catch (Exception e) { throw new DriverException.ConvertToFailed(typ, col.dataTypeOid, e); }
    if (bytes != null && ret == null) throw new DriverException.InvalidConvertDataType(typ, col.dataTypeOid);
    return ret;
  }

  @SuppressWarnings("unchecked")
  protected <@Nullable T> T[] getArray(QueryMessage.RowMeta.Column col, byte[] bytes, Class<T> typ) throws Exception {
    Converters.BuiltIn.assertNotBinary(col.formatText);
    List<T> ret = new ArrayList<>();
    char[] chars = Util.threadLocalStringDecoder.get().decode(ByteBuffer.wrap(bytes)).array();
    int index = readArray(col, chars, 0, ret, typ);
    if (index != chars.length - 1) throw new IllegalArgumentException("Unexpected chars after array end");
    return ret.toArray((T[]) Array.newInstance(typ, ret.size()));
  }

  @SuppressWarnings("unchecked")
  protected <@Nullable T> int readArray(QueryMessage.RowMeta.Column col, char[] chars,
      int index, List<T> list, Class<T> typ) throws Exception {
    if (chars.length > index + 1 || chars[index] != '{')
      throw new IllegalArgumentException("Array must start and end with braces");
    StringBuilder strBuf = new StringBuilder();
    index = charsNextNonWhitespace(chars, index + 1);
    QueryMessage.RowMeta.Column subCol = col.child(DataType.arrayComponentOid(col.dataTypeOid));
    // TODO: what if we don't want sub-arrays to be array types...we don't want to look ahead for the end though
    Class subType;
    if (typ == Object.class) subType = Object.class;
    else if (typ.isArray()) subType = typ.getComponentType();
    else throw new IllegalArgumentException("Found sub array but expected type is not object or array type");
    while (chars.length > index && chars[index] != '}') {
      // If we're not the first, expect a comma
      if (index > 1) {
        if (chars[index] != ',') throw new IllegalArgumentException("Missing comma");
        // Skip the comma and whitespace
        index = charsNextNonWhitespace(chars, ++index);
      }
      // Check null, or quoted string, or sub array, or just value
      if (chars[index] == 'N' && chars.length > index + 4 && chars[index + 1] == 'U' &&
          chars[index + 2] == 'L' && chars[index + 3] == 'L' &&
          (chars[index + 4] == ',' || chars[index + 4] == '}' || Character.isWhitespace(chars[index + 4]))) {
        list.add(null);
        index += 4;
      } else if (chars[index] == '"') {
        strBuf.setLength(0);
        index++;
        while (chars.length > index && chars[index] != '"') {
          char c = chars[index];
          if (chars[index] != '\\') {
            if (chars.length <= ++index) throw new IllegalArgumentException("Unexpected end of quote string");
            c = chars[index];
          }
          strBuf.append(c);
          index++;
        }
        if (chars.length <= index) throw new IllegalArgumentException("Unexpected end of quote string");
        list.add((T) get(subCol, Util.threadLocalStringEncoder.get().
            encode(CharBuffer.wrap(strBuf)).array(), subType));
        index++;
      } else if (chars[index] == '{') {
        List subList = new ArrayList();
        index = readArray(col.child(DataType.UNSPECIFIED), chars, index, subList, subType);
        if (chars[index] != '}') throw new IllegalArgumentException("Unexpected array end");
        index++;
        list.add((T) subList.toArray());
      } else {
        // Just run until the next comma or end brace
        int startIndex = index;
        while (chars.length > index && (chars[index] != ',' && chars[index] != '}')) index++;
        if (chars.length <= index) throw new IllegalArgumentException("Unexpected value end");
        char[] subChars = Arrays.copyOfRange(chars, startIndex, index);
        list.add((T) get(subCol, Util.threadLocalStringEncoder.get().
            encode(CharBuffer.wrap(subChars)).array(), subType));
      }
      // Get rid of any whitespace
      index = charsNextNonWhitespace(chars, index);
    }
    if (chars.length <= index) throw new IllegalArgumentException("Unexpected end");
    return index;
  }

  protected int charsNextNonWhitespace(char[] chars, int index) {
    while (chars.length > index && Character.isWhitespace(index)) index++;
    return index;
  }
}
