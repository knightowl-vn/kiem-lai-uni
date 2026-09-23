package com.universe.wiki.entry.dto.appreciation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Bộ giải tuần tự hóa JSON nghiêm ngặt cho điểm đánh giá bài viết Wiki.
 *
 * <p>Đảm bảo:
 * <ul>
 *   <li>Chỉ chấp nhận token số nguyên bản (VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT);</li>
 *   <li>Đọc trực tiếp qua {@link JsonParser#getDecimalValue()} để bảo toàn chính xác chuỗi ký tự gốc,
 *       tuyệt đối không ép kiểu qua double hay DoubleNode làm mất độ chính xác;</li>
 *   <li>Từ chối chuỗi ký tự ("4.5"), boolean, null, array, object;</li>
 *   <li>Từ chối các giá trị ngoài thang đo 1.0 đến 5.0;</li>
 *   <li>Từ chối các giá trị không chia hết cho bước nhảy 0.5 (như 4.7, 4.5000000000000001, 1.25).</li>
 * </ul>
 */
public class StrictHalfStarScoreDeserializer extends JsonDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
            throw new IllegalArgumentException("Mức độ yêu thích phải là số hợp lệ từ 1.0 đến 5.0 (bước 0.5).");
        }

        BigDecimal decimal = p.getDecimalValue();
        if (decimal == null) {
            throw new IllegalArgumentException("Mức độ yêu thích không được để trống.");
        }

        if (decimal.compareTo(WikiAppreciationScore.MIN_STARS) < 0 || decimal.compareTo(WikiAppreciationScore.MAX_STARS) > 0) {
            throw new IllegalArgumentException(
                    "Mức độ yêu thích phải từ " + WikiAppreciationScore.MIN_STARS
                            + " đến " + WikiAppreciationScore.MAX_STARS + " sao."
            );
        }

        BigDecimal doubled = decimal.multiply(BigDecimal.valueOf(2));
        if (doubled.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Mức độ yêu thích phải theo bước 0.5 sao.");
        }

        return decimal;
    }

    @Override
    public BigDecimal getNullValue(DeserializationContext ctxt) {
        return null;
    }
}
