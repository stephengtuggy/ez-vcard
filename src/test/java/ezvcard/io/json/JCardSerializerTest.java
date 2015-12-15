package ezvcard.io.json;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ezvcard.VCard;


/**
 * @author Buddy Gorven
 */
public class JCardSerializerTest {
	
	@BeforeClass
	public static void configure() {
		JCardSerializer.addProdIdByDefault(false);
	}

	@Test
	public void serialize_single_vcard() throws Throwable {

		VCard example = JCardWriterTest.createExample();
		String actual = toString(example);
		JCardWriterTest.assertExample(actual, "jcard-example.json");
	}
	
	@Test
	public void serialize_multiple_vcards() throws Throwable {

		List<VCard> cards = new ArrayList<VCard>();
		VCard vcard = new VCard();
		vcard.setFormattedName("John Doe");
		cards.add(vcard);

		vcard = new VCard();
		vcard.setFormattedName("Jane Doe");
		cards.add(vcard);
		
		String actual = toString(cards);

		//@formatter:off
		String expected =
		"[" +
			"[\"vcard\"," +
				"[" +
					"[\"version\",{},\"text\",\"4.0\"]," +
					"[\"fn\",{},\"text\",\"John Doe\"]" +
				"]" +
			"]," +
			"[\"vcard\"," +
				"[" +
					"[\"version\",{},\"text\",\"4.0\"]," +
					"[\"fn\",{},\"text\",\"Jane Doe\"]" +
				"]" +
			"]" +
		"]";
		//@formatter:on
		assertEquals(expected, actual);
	}
	
	public String toString(Object example) throws IOException, JsonGenerationException, JsonMappingException {
		StringWriter result = new StringWriter();
		ObjectMapper mapper = new ObjectMapper();
		mapper.writeValue(result, example);
		return result.toString();
	}
}
