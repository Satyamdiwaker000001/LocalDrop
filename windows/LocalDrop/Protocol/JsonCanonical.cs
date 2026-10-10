// M02: minimal canonical JSON subset, dependency-free (pure C#, BCL only).
// Supports exactly what the protocol needs: objects (string keys), arrays,
// strings, 64-bit integers, booleans. No floats, no exponents, no null
// production. Canonical rules (shared-protocol/framing-resume-integrity.md):
// no whitespace, contract key order (list order), fixed string escaping,
// integers only. Parsers accept any key order and JSON whitespace; unknown
// fields are ignored by the schema layer.
using System.Text;
using System.Text.RegularExpressions;

namespace LocalDrop.Protocol;

public abstract record JsonValue
{
    public sealed record Obj(List<KeyValuePair<string, JsonValue>> Entries) : JsonValue;
    public sealed record Arr(List<JsonValue> Items) : JsonValue;
    public sealed record Str(string Value) : JsonValue;
    public sealed record Num(long Value) : JsonValue;
    public sealed record Bool(bool Value) : JsonValue;

    public Obj AsObj() => (Obj)this;
}

public abstract record JsonParseResult
{
    public sealed record Ok(JsonValue Value) : JsonParseResult;
    public sealed record Err(string Detail) : JsonParseResult;
}

public static class JsonCanonical
{
    public const int MaxDepth = 32;

    // ---- serialization (canonical) ----

    public static string Serialize(JsonValue value)
    {
        var sb = new StringBuilder();
        WriteValue(sb, value);
        return sb.ToString();
    }

    private static void WriteValue(StringBuilder sb, JsonValue value)
    {
        switch (value)
        {
            case JsonValue.Obj o:
                sb.Append('{');
                bool first = true;
                foreach (var kv in o.Entries)
                {
                    if (!first) sb.Append(',');
                    first = false;
                    WriteString(sb, kv.Key);
                    sb.Append(':');
                    WriteValue(sb, kv.Value);
                }
                sb.Append('}');
                break;
            case JsonValue.Arr a:
                sb.Append('[');
                bool af = true;
                foreach (var item in a.Items)
                {
                    if (!af) sb.Append(',');
                    af = false;
                    WriteValue(sb, item);
                }
                sb.Append(']');
                break;
            case JsonValue.Str s:
                WriteString(sb, s.Value);
                break;
            case JsonValue.Num n:
                sb.Append(n.Value.ToString(System.Globalization.CultureInfo.InvariantCulture));
                break;
            case JsonValue.Bool b:
                sb.Append(b.Value ? "true" : "false");
                break;
            default:
                throw new ArgumentException("unknown JsonValue");
        }
    }

    public static void WriteString(StringBuilder sb, string s)
    {
        sb.Append('"');
        foreach (char c in s)
        {
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\b': sb.Append("\\b"); break;
                case '\f': sb.Append("\\f"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (c < ' ')
                    {
                        sb.Append("\\u00");
                        sb.Append("0123456789abcdef"[(c >> 4) & 0xF]);
                        sb.Append("0123456789abcdef"[c & 0xF]);
                    }
                    else
                    {
                        if (char.IsSurrogate(c))
                            throw new ArgumentException("lone surrogate in output string");
                        sb.Append(c);
                    }
                    break;
            }
        }
        sb.Append('"');
    }

    // ---- parsing ----

    public static JsonParseResult Parse(string text) => new Parser(text).ParseRoot();

    private sealed class Parser(string text)
    {
        private int _pos;
        private string _error = "invalid JSON";

        public JsonParseResult ParseRoot()
        {
            var v = ParseValue(0);
            if (v is null) return new JsonParseResult.Err(_error);
            SkipWs();
            if (_pos < text.Length) return new JsonParseResult.Err("trailing content after JSON value");
            return new JsonParseResult.Ok(v);
        }

        private bool AtEnd() => _pos >= text.Length;

        private void SkipWs()
        {
            while (_pos < text.Length && (text[_pos] == ' ' || text[_pos] == '\t' ||
                   text[_pos] == '\n' || text[_pos] == '\r')) _pos++;
        }

        private JsonValue? Fail(string detail)
        {
            _error = detail;
            return null;
        }

        private JsonValue? ParseValue(int depth)
        {
            if (depth > MaxDepth) return Fail("nesting too deep");
            SkipWs();
            if (AtEnd()) return Fail("unexpected end of input");
            char c = text[_pos];
            if (c == '{') return ParseObject(depth);
            if (c == '[') return ParseArray(depth);
            if (c == '"')
            {
                var s = ParseString();
                return s is null ? null : new JsonValue.Str(s);
            }
            if (c == 't') return ParseLiteral("true") ? new JsonValue.Bool(true) : null;
            if (c == 'f') return ParseLiteral("false") ? new JsonValue.Bool(false) : null;
            if (c == 'n') return Fail("'null' is not a valid protocol value");
            if (c == '-' || (c >= '0' && c <= '9')) return ParseNumber();
            return Fail($"unexpected character '{c}'");
        }

        private bool ParseLiteral(string word)
        {
            if (text.AsSpan(_pos).StartsWith(word.AsSpan()))
            {
                _pos += word.Length;
                return true;
            }
            Fail("invalid literal");
            return false;
        }

        private JsonValue? ParseObject(int depth)
        {
            _pos++; // {
            var map = new List<KeyValuePair<string, JsonValue>>();
            SkipWs();
            if (!AtEnd() && text[_pos] == '}')
            {
                _pos++;
                return new JsonValue.Obj(map);
            }
            while (true)
            {
                SkipWs();
                if (AtEnd() || text[_pos] != '"') return Fail("expected string key");
                var key = ParseString();
                if (key is null) return null;
                SkipWs();
                if (AtEnd() || text[_pos] != ':') return Fail("expected ':'");
                _pos++;
                var v = ParseValue(depth + 1);
                if (v is null) return null;
                map.Add(new KeyValuePair<string, JsonValue>(key, v));
                SkipWs();
                if (AtEnd()) return Fail("unterminated object");
                if (text[_pos] == ',') _pos++;
                else if (text[_pos] == '}')
                {
                    _pos++;
                    return new JsonValue.Obj(map);
                }
                else return Fail("expected ',' or '}'");
            }
        }

        private JsonValue? ParseArray(int depth)
        {
            _pos++; // [
            var items = new List<JsonValue>();
            SkipWs();
            if (!AtEnd() && text[_pos] == ']')
            {
                _pos++;
                return new JsonValue.Arr(items);
            }
            while (true)
            {
                var v = ParseValue(depth + 1);
                if (v is null) return null;
                items.Add(v);
                SkipWs();
                if (AtEnd()) return Fail("unterminated array");
                if (text[_pos] == ',') _pos++;
                else if (text[_pos] == ']')
                {
                    _pos++;
                    return new JsonValue.Arr(items);
                }
                else return Fail("expected ',' or ']'");
            }
        }

        private string? ParseString()
        {
            _pos++; // opening quote
            var sb = new StringBuilder();
            while (true)
            {
                if (_pos >= text.Length) { Fail("unterminated string"); return null; }
                char c = text[_pos++];
                if (c == '"') return sb.ToString();
                if (c == '\\')
                {
                    if (_pos >= text.Length) { Fail("unterminated escape"); return null; }
                    char e = text[_pos++];
                    switch (e)
                    {
                        case '"': case '\\': case '/': sb.Append(e); break;
                        case 'b': sb.Append('\b'); break;
                        case 'f': sb.Append('\f'); break;
                        case 'n': sb.Append('\n'); break;
                        case 'r': sb.Append('\r'); break;
                        case 't': sb.Append('\t'); break;
                        case 'u':
                            if (_pos + 4 > text.Length) { Fail("bad \\u escape"); return null; }
                            if (!int.TryParse(text.Substring(_pos, 4),
                                    System.Globalization.NumberStyles.HexNumber, null, out int code))
                            { Fail("bad \\u escape"); return null; }
                            _pos += 4;
                            char ch = (char)code;
                            if (char.IsHighSurrogate(ch))
                            {
                                if (_pos + 6 <= text.Length && text[_pos] == '\\' && text[_pos + 1] == 'u' &&
                                    int.TryParse(text.Substring(_pos + 2, 4),
                                        System.Globalization.NumberStyles.HexNumber, null, out int lo) &&
                                    char.IsLowSurrogate((char)lo))
                                {
                                    _pos += 6;
                                    sb.Append(ch);
                                    sb.Append((char)lo);
                                    continue;
                                }
                                Fail("lone surrogate in string");
                                return null;
                            }
                            if (char.IsLowSurrogate(ch)) { Fail("lone surrogate in string"); return null; }
                            sb.Append(ch);
                            break;
                        default: Fail($"bad escape '\\{e}'"); return null;
                    }
                }
                else
                {
                    if (c < ' ') { Fail("unescaped control character"); return null; }
                    sb.Append(c);
                }
            }
        }

        private JsonValue? ParseNumber()
        {
            int start = _pos;
            if (!AtEnd() && text[_pos] == '-') _pos++;
            if (AtEnd()) return Fail("bad number");
            if (text[_pos] == '0') _pos++;
            else if (text[_pos] >= '1' && text[_pos] <= '9')
            {
                while (!AtEnd() && text[_pos] >= '0' && text[_pos] <= '9') _pos++;
            }
            else return Fail("bad number");
            if (!AtEnd() && (text[_pos] == '.' || text[_pos] == 'e' || text[_pos] == 'E'))
                return Fail("non-integer numbers are not valid protocol values");
            var token = text.Substring(start, _pos - start);
            if (!long.TryParse(token, System.Globalization.NumberStyles.Integer,
                    System.Globalization.CultureInfo.InvariantCulture, out long v))
                return Fail("integer out of range");
            return new JsonValue.Num(v);
        }
    }
}

// ---- schema reading helper ----

// Outcome of payload/object parsing. Failures map to INVALID_PROTOCOL.
public abstract record PayloadResult<T>
{
    public sealed record Ok(T Value) : PayloadResult<T>;
    public sealed record Fail(string Detail) : PayloadResult<T>;
}

// Reads + validates typed fields from a parsed JSON object. Records the
// first error; callers check Error() before using values.
public sealed class FieldReader
{
    private readonly Dictionary<string, JsonValue> _obj;
    private readonly string _context;
    private string? _firstError;

    public FieldReader(IReadOnlyList<KeyValuePair<string, JsonValue>> entries, string context)
    {
        _obj = new Dictionary<string, JsonValue>();
        foreach (var kv in entries) _obj[kv.Key] = kv.Value;
        _context = context;
    }

    public bool Contains(string key) => _obj.ContainsKey(key);

    public string? Error() => _firstError;

    private void Note(string detail)
    {
        _firstError ??= $"{_context}.{detail}";
    }

    private JsonValue? Raw(string key, bool required)
    {
        if (!_obj.TryGetValue(key, out var v))
        {
            if (required) Note($"{key}: missing required field");
            return null;
        }
        return v;
    }

    public string? GetString(string key, int minLen, int maxLen, bool required = true)
    {
        var v = Raw(key, required);
        if (v is null) return null;
        if (v is not JsonValue.Str s)
        {
            Note($"{key}: expected string");
            return null;
        }
        if (s.Value.Length < minLen || s.Value.Length > maxLen)
        {
            Note($"{key}: length out of range ({minLen}..{maxLen})");
            return null;
        }
        return s.Value;
    }

    public string? GetUuid(string key, bool required = true)
    {
        var s = GetString(key, 36, 36, required);
        if (s is null) return null;
        if (!Uuids.IsValid(s))
        {
            Note($"{key}: invalid UUID syntax");
            return null;
        }
        return s.ToLowerInvariant();
    }

    public long? GetInt(string key, long min, long max, bool required = true)
    {
        var v = Raw(key, required);
        if (v is null) return null;
        if (v is not JsonValue.Num n)
        {
            Note($"{key}: expected integer");
            return null;
        }
        if (n.Value < min || n.Value > max)
        {
            Note($"{key}: out of range ({min}..{max})");
            return null;
        }
        return n.Value;
    }

    public bool? GetBool(string key, bool required = true)
    {
        var v = Raw(key, required);
        if (v is null) return null;
        if (v is not JsonValue.Bool b)
        {
            Note($"{key}: expected boolean");
            return null;
        }
        return b.Value;
    }

    public IReadOnlyList<KeyValuePair<string, JsonValue>>? GetObj(string key, bool required = true)
    {
        var v = Raw(key, required);
        if (v is null) return null;
        if (v is not JsonValue.Obj o)
        {
            Note($"{key}: expected object");
            return null;
        }
        return o.Entries;
    }

    public List<string>? GetStringList(string key, int maxItems, int itemMin, int itemMax)
    {
        var v = Raw(key, true);
        if (v is null) return null;
        if (v is not JsonValue.Arr a)
        {
            Note($"{key}: expected array");
            return null;
        }
        if (a.Items.Count > maxItems)
        {
            Note($"{key}: too many entries (max {maxItems})");
            return null;
        }
        var outList = new List<string>(a.Items.Count);
        for (int i = 0; i < a.Items.Count; i++)
        {
            if (a.Items[i] is not JsonValue.Str s)
            {
                Note($"{key}[{i}]: expected string");
                return null;
            }
            if (s.Value.Length < itemMin || s.Value.Length > itemMax)
            {
                Note($"{key}[{i}]: length out of range ({itemMin}..{itemMax})");
                return null;
            }
            outList.Add(s.Value);
        }
        return outList;
    }
}

// UUID syntax validation (accepts any case; output is normalized lowercase).
// No network, no crypto: pure shape check.
public static class Uuids
{
    private static readonly Regex Re = new(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        RegexOptions.Compiled);

    public static bool IsValid(string s) => Re.IsMatch(s);

    public static string Random() => Guid.NewGuid().ToString("D");
}
