package az.kampus;
import java.util.*;
class EmvQr {
 static String tlv(String tag,String value){if(value.length()>99)throw new IllegalArgumentException("TLV too long");return tag+String.format("%02d",value.length())+value;}
 static String crc(String text){int crc=0xffff;for(byte b:text.getBytes(java.nio.charset.StandardCharsets.UTF_8)){crc^=(b&255)<<8;for(int i=0;i<8;i++)crc=(crc&0x8000)!=0?(crc<<1)^0x1021:crc<<1;crc&=0xffff;}return String.format("%04X",crc);}
 static String create(String merchant,String intent,long amount){String q=tlv("00","01")+tlv("01",intent==null?"11":"12")+tlv("26",tlv("00","az.kampus")+tlv("01",merchant))+tlv("52","5812")+tlv("53","944")+(amount>0?tlv("54",String.format(Locale.ROOT,"%.2f",amount/100.0)):"")+tlv("58","AZ")+tlv("59","Kafe Xazar")+tlv("60","Baki")+(intent==null?"":tlv("62",tlv("05",intent)))+"6304";return q+crc(q);}
 static Map<String,String> parse(String qr){Problem.require(qr.length()>12&&qr.length()<1024&&qr.substring(qr.length()-8,qr.length()-4).equals("6304")&&crc(qr.substring(0,qr.length()-4)).equalsIgnoreCase(qr.substring(qr.length()-4)),"INVALID_QR_CRC","QR kod zədələnib.");return fields(qr);}
 static Map<String,String> fields(String text){var m=new HashMap<String,String>();try{for(int i=0;i<text.length();){String tag=text.substring(i,i+2);int len=Integer.parseInt(text.substring(i+2,i+4));Problem.require(!m.containsKey(tag),"INVALID_QR","Təkrar QR sahəsi.");m.put(tag,text.substring(i+4,i+4+len));i+=4+len;}}catch(RuntimeException e){throw new Problem(422,"INVALID_QR","QR formatı yanlışdır.");}return m;}
}
