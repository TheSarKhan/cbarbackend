package az.kampus;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static az.kampus.Store.*;

/**
 * The open-banking consent the customer signs with SİMA: what Kampus may read, the payments it may start, the limit, the
 * period and the customer's rights. The same structure is shown as a paper preview in the app and rendered into the PDF.
 */
final class ConsentDocument {
 private ConsentDocument(){}
 static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC);
 static final DateTimeFormatter DATE_TIME=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

 static String money(long minor){return String.format(Locale.ROOT,"%,.2f AZN",minor/100.0).replace(',',' ');}
 static String months(int n){return n+" ay";}

 /**
  * @param signature null while the document is only a preview, else {contractId, signatureHash, signedAt, method}
  */
 static Map<String,Object> build(Map<String,Object> consent,String personName,String maskedFin,String phone,List<Map<String,Object>> cards,Instant from,Map<String,Object> signature){
  int months=(int)num(consent,"months");
  long limit=num(consent,"monthlyLimitMinor");
  Instant to=from.plus(Duration.ofDays(30L*months));
  String bank=str(consent,"bankName");
  var doc=new LinkedHashMap<String,Object>();
  doc.put("title","Açıq bankçılıq üzrə məlumat və ödəniş icazəsi");
  doc.put("reference","OB-"+Auth.sha256(str(consent,"id")).substring(0,10).toUpperCase(Locale.ROOT));
  doc.put("parties",List.of(
   m("label","Müştəri","value",personName+" · FİN "+maskedFin+" · "+phone),
   m("label","Hesabları idarə edən bank","value",bank),
   m("label","Üçüncü tərəf provayder","value","Kampus rəqəmsal cüzdanı (hesab məlumatı və ödəniş başlatma provayderi, sandbox)")));
  var cardLines=new ArrayList<String>();
  for(var c:cards)cardLines.add(str(c,"name")+" · "+str(c,"masked"));
  doc.put("terms",List.of(
   m("label","İcazə müddəti","value",months(months)+" ("+DATE.format(from)+" – "+DATE.format(to)+")"),
   m("label","Aylıq ödəniş limiti","value",money(limit)),
   m("label","Seçilmiş kart və hesablar","value",String.join("; ",cardLines))));
  doc.put("sections",List.of(
   m("heading","1. İcazənin predmeti","lines",List.of(
    "Müştəri yuxarıda göstərilən kart və hesablarına aid aşağıdakı məlumatların Kampus tərəfindən oxunmasına və ödənişlərin Kampus vasitəsilə başladılmasına razılıq verir.")),
   m("heading","2. Oxunan məlumatlar","lines",List.of(
    "Seçilmiş kart və hesabların balansı və kartın son rəqəmləri.",
    "Son 90 günün əməliyyat tarixçəsi (məbləğ, tarix, satıcı və kateqoriya kodu).",
    "Şəxsi identifikasiya məlumatları (ad, telefon, FİN) bankdan alınmır və Kampus-a verilmir.")),
   m("heading","3. Ödənişlər","lines",List.of(
    "Kampus yalnız müştərinin özünün təsdiq etdiyi ödənişləri başlada bilər: QR ödənişi, P2P köçürmə və Kampus balansının artırılması.",
    "Hər ödəniş Kampus-da PIN ilə, böyük məbləğlərdə isə əlavə üz yoxlaması ilə təsdiqlənir.",
    "Bütün ödənişlərin cəmi icazə müddətində hər ay üçün "+money(limit)+" limitini keçə bilməz. Limiti bank özü də tətbiq edir.")),
   m("heading","4. Müddət və ləğv","lines",List.of(
    "İcazə "+months(months)+" etibarlıdır və müddət bitəndə avtomatik dayanır.",
    "Müştəri icazəni istənilən vaxt bankın tətbiqinin «Açıq bankçılıq» bölməsindən və ya Kampus-dan ləğv edə bilər. Ləğvdən sonra Kampus bu hesablara daxil ola bilməz və toplanmış hesab məlumatları silinir.")),
   m("heading","5. Məlumatların qorunması","lines",List.of(
    "Məlumatlar yalnız müştərinin hesablarını və xərclərini göstərmək üçün istifadə olunur, üçüncü şəxslərə verilmir.",
    "Müştəri Kampus-da və bankda öz məlumatlarına baxmaq, düzəliş və silinmə tələb etmək hüququna malikdir.")),
   m("heading","6. İmza","lines",List.of(
    "Sənəd müştərinin şəxsiyyətini üz yoxlaması ilə təsdiq edən SİMA elektron imzası ilə imzalanır. Sandbox mühitində imza hüquqi qüvvəyə malik deyil."))));
  doc.put("signature",signature);
  doc.put("signed",signature!=null);
  return doc;
 }

 /** Hash of what the customer actually agrees to; it is what the SİMA signature is bound to. */
 static String contentHash(Map<String,Object> doc){
  var copy=new LinkedHashMap<>(doc);copy.remove("signature");copy.remove("signed");
  return Auth.sha256(copy.toString());
 }

 @SuppressWarnings("unchecked")
 static byte[] pdf(Map<String,Object> doc,String bankName){
  try(var out=new ByteArrayOutputStream()){
   BaseFont regular=font("fonts/DejaVuSans.ttf"),bold=font("fonts/DejaVuSans-Bold.ttf");
   Font title=new Font(bold,17,Font.NORMAL,new Color(0x2a,0x0a,0x8a)),h=new Font(bold,11.5f),body=new Font(regular,10),small=new Font(regular,8.5f,Font.NORMAL,Color.GRAY),label=new Font(regular,8.5f,Font.NORMAL,Color.GRAY),strong=new Font(bold,10);
   var document=new Document(PageSize.A4,56,56,56,56);
   PdfWriter.getInstance(document,out);
   document.addTitle(Objects.toString(doc.get("title")));document.addCreator("Kampus");
   document.open();
   var head=new Paragraph(bankName+" · Kampus",small);head.setSpacingAfter(6);document.add(head);
   var t=new Paragraph(Objects.toString(doc.get("title")),title);t.setSpacingAfter(4);document.add(t);
   var ref=new Paragraph("Sənəd № "+doc.get("reference"),small);ref.setSpacingAfter(14);document.add(ref);
   for(var key:List.of("parties","terms")){
    var table=new PdfPTable(new float[]{32,68});table.setWidthPercentage(100);table.setSpacingAfter(10);
    for(var row:(List<Map<String,Object>>)doc.get(key)){
     var l=new PdfPCell(new Phrase(str(row,"label"),label));var v=new PdfPCell(new Phrase(str(row,"value"),strong));
     for(var cell:List.of(l,v)){cell.setBorder(Rectangle.BOTTOM);cell.setBorderColor(new Color(0xe5,0xe2,0xf0));cell.setPadding(6);}
     table.addCell(l);table.addCell(v);
    }
    document.add(table);
   }
   for(var section:(List<Map<String,Object>>)doc.get("sections")){
    var heading=new Paragraph(str(section,"heading"),h);heading.setSpacingBefore(8);heading.setSpacingAfter(3);document.add(heading);
    for(var line:(List<String>)section.get("lines")){var p=new Paragraph(line,body);p.setLeading(14);p.setSpacingAfter(3);document.add(p);}
   }
   var signature=(Map<String,Object>)doc.get("signature");
   var box=new PdfPTable(1);box.setWidthPercentage(100);box.setSpacingBefore(18);
   var cell=new PdfPCell();cell.setPadding(12);cell.setBorderColor(signature==null?new Color(0xcc,0xcc,0xcc):new Color(0x2f,0x9e,0x63));
   if(signature==null){cell.addElement(new Paragraph("İmza gözlənilir. Sənəd SİMA ilə imzalandıqdan sonra bu sahədə imza məlumatları görünəcək.",small));}
   else{
    cell.addElement(new Paragraph("SİMA ilə imzalanıb",new Font(bold,11,Font.NORMAL,new Color(0x2f,0x9e,0x63))));
    cell.addElement(new Paragraph("Müqavilə №: "+signature.get("contractId"),body));
    cell.addElement(new Paragraph("İmza tarixi: "+signature.get("signedAt"),body));
    cell.addElement(new Paragraph("İmza heşi: "+signature.get("signatureHash"),small));
   }
   box.addCell(cell);document.add(box);
   document.close();
   return out.toByteArray();
  }catch(Exception e){throw new IllegalStateException("Sənəd yaradıla bilmədi",e);}
 }

 private static BaseFont font(String path)throws Exception{
  try(InputStream in=ConsentDocument.class.getClassLoader().getResourceAsStream(path)){
   byte[] bytes=in.readAllBytes();
   return BaseFont.createFont(path,BaseFont.IDENTITY_H,BaseFont.EMBEDDED,true,bytes,null);
  }
 }
}
