/*

(C) Copyright 2015-2017 Alberto Fernández <infjaf@gmail.com>
(C) Copyright 2004,2014 Jan Schlößin
(C) Copyright 2003-2004 Anil Kumar K <anil@linuxense.com>

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 3.0 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library.  If not, see <http://www.gnu.org/licenses/>.

*/


package com.linuxense.javadbf;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DBFReader class can creates objects to represent DBF data.
 * 
 * This Class is used to read data from a DBF file. Meta data and records can be
 * queried against this document.
 * 
 * <p>
 * DBFReader cannot write to a DBF file. For creating DBF files use DBFWriter.
 * 
 * <p>
 * Fetching records is possible only in the forward direction and cannot be
 * re-wound. In such situations, a suggested approach is to reconstruct the
 * object.
 * 
 * <p>
 * The nextRecord() method returns an array of Objects and the types of these
 * Object are as follows:
 * 
 * <table>
 * <tr>
 * <th>xBase Type</th>
 * <th>Java Type</th>
 * </tr>
 * 
 * <tr>
 * <td>C</td>
 * <td>String</td>
 * </tr>
 * <tr>
 * <td>N</td>
 * <td>java.math.BigDecimal</td>
 * </tr>
 * <tr>
 * <td>F</td>
 * <td>java.math.BigDecimal</td>
 * </tr>
 * <tr>
 * <td>L</td>
 * <td>Boolean</td>
 * </tr>
 * <tr>
 * <td>D</td>
 * <td>java.util.Date</td>
 * </tr>
 * <tr>
 * <td>Y</td>
 * <td>java.math.BigDecimal</td>
 * </tr>
 * <tr>
 * <td>I</td>
 * <td>Integer</td>
 * </tr>
 * <tr>
 * <td>T</td>
 * <td>java.util.Date</td>
 * </tr>
 * </table>
 */
public class DBFReader extends DBFBase {

	private static final long MILLISECS_PER_DAY = 24*60*60*1000;
	private static final long TIME_MILLIS_1_1_4713_BC = -210866803200000L;

	private DataInputStream dataInputStream;
	private DBFHeader header;
	private boolean trimRightSpaces = true;
	
	private DBFMemoFile memoFile = null;
	
	
	/**
	 * Intializes a DBFReader object.
	 * 
	 * Tries to detect charset from file, if failed uses default charset ISO-8859-1
	 * When this constructor returns the object will have completed reading the
	 * header (meta date) and header information can be queried there on. And it
	 * will be ready to return the first row.
	 * 
	 * @param in  the InputStream where the data is read from.
	 * @throws DBFException
	 */
	public DBFReader(InputStream in) throws DBFException {
		this(in,null);
	}
	
	/**
	 * Initializes a DBFReader object.
	 * 
	 * When this constructor returns the object will have completed reading the
	 * header (meta date) and header information can be queried there on. And it
	 * will be ready to return the first row.
	 * 
	 * @param in the InputStream where the data is read from.
	 * @param charset charset used to decode field names and field contents. If null, then is autedetected from dbf file
	 */
	public DBFReader(InputStream in,Charset charset) throws DBFException {
		try {
			
			this.dataInputStream = new DataInputStream(in);
			this.header = new DBFHeader();
			this.header.read(this.dataInputStream, charset);
			setCharset(this.header.getUsedCharset());
			
			/* it might be required to leap to the start of records at times */
			int fieldSize = this.header.getFieldDescriptorSize();
			int tableSize = this.header.getTableHeaderSize();
			int t_dataStartIndex = this.header.headerLength - (tableSize + (fieldSize * this.header.fieldArray.length)) - 1;
			skip(t_dataStartIndex);
		} catch (IOException e) {
			throw new DBFException(e.getMessage(), e);
		}
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(128);
		sb.append(this.header.getYear()).append("/");
		sb.append(this.header.getMonth()).append("/");
		sb.append(this.header.getDay()).append("\n");
		sb.append("Total records: ").append(this.header.numberOfRecords).append("\n");
		sb.append("Header length: ").append(this.header.headerLength).append("\n");
		sb.append("Columns:\n");
		for (DBFField field : this.header.fieldArray) {
			sb.append(field.getName());
			sb.append("\n");
		}
		return sb.toString();
	}

	/**
		Returns the number of records in the DBF.
	*/
	public int getRecordCount() {
		return this.header.numberOfRecords;
	}
	/**
	 * Returns the last time the file was modified
	 * @return the las time the file was modified
	 */
	public Date getLastModificationDate() {
		if (this.header != null) {
			return this.header.getLastModificationDate();
		}
		return null;
	}


	/**
	 * Returns the asked Field. In case of an invalid index, it returns a
	 * ArrayIndexOutofboundsException.
	 * 
	 * @param index Index of the field. Index of the first field is zero.
	 */
	public DBFField getField(int index) throws DBFException {
		return this.header.fieldArray[index];
	}

	/**
	 * Returns the number of field in the DBF.
	 */
	public int getFieldCount() throws DBFException {
		return this.header.fieldArray.length;
	}

	/**
	 * Reads the returns the next row in the DBF stream.
	 * 
	 * @return The next row as an Object array. Types of the elements these
	 *          arrays follow the convention mentioned in the class description.
	 */
	public Object[] nextRecord() throws DBFException {

		Object recordObjects[] = new Object[this.header.fieldArray.length];
		try {
			boolean isDeleted = false;
			do {
				if (isDeleted) {
					skip(this.header.recordLength - 1);
				}
				int t_byte = this.dataInputStream.readByte();
				if (t_byte == END_OF_DATA) {
					return null;
				}
				isDeleted = t_byte == '*';
			} while (isDeleted);

			for (int i = 0; i < this.header.fieldArray.length; i++) {
				DBFField field = this.header.fieldArray[i];
				switch (field.getType()) {
				case CHARACTER:
					byte b_array[] = new byte[field.getFieldLength()];
					this.dataInputStream.read(b_array);
					if (this.trimRightSpaces) {
						recordObjects[i] = new String(DBFUtils.trimRightSpaces(b_array), getCharset());
					}
					else {
						recordObjects[i] = new String(b_array, getCharset());
					}
					break;

				case DATE:

					byte t_byte_year[] = new byte[4];
					this.dataInputStream.read(t_byte_year);

					byte t_byte_month[] = new byte[2];
					this.dataInputStream.read(t_byte_month);

					byte t_byte_day[] = new byte[2];
					this.dataInputStream.read(t_byte_day);

					try {
						GregorianCalendar calendar = new GregorianCalendar(Integer.parseInt(new String(t_byte_year, StandardCharsets.US_ASCII)),
								Integer.parseInt(new String(t_byte_month, StandardCharsets.US_ASCII)) - 1,
								Integer.parseInt(new String(t_byte_day, StandardCharsets.US_ASCII)));
						recordObjects[i] = calendar.getTime();
					} catch (NumberFormatException e) {
						// this field may be empty or may have improper value set
						recordObjects[i] = null;
					}

					break;

				case FLOATING_POINT:
				case NUMERIC:
					recordObjects[i] = DBFUtils.readNumericStoredAsText(this.dataInputStream, field.getFieldLength());
					break;

				case LOGICAL:
					byte t_logical = this.dataInputStream.readByte();
					recordObjects[i] = DBFUtils.toBoolean(t_logical);
					break;
				case LONG:
				case AUTOINCREMENT:
					int data = DBFUtils.readLittleEndianInt(this.dataInputStream);
					recordObjects[i] = data;
					break;
				case CURRENCY:
					int c_data = DBFUtils.readLittleEndianInt(this.dataInputStream);
					String s_data = String.format("%05d", c_data);
					String x1 = s_data.substring(0, s_data.length() - 4);
					String x2 = s_data.substring(s_data.length() - 4);
					recordObjects[i] = new BigDecimal(x1 + "." + x2);
					skip(field.getFieldLength() - 4);
					break;
				case TIMESTAMP:
				case TIMESTAMP_DBASE7:
					int days = DBFUtils.readLittleEndianInt(this.dataInputStream);
					int time = DBFUtils.readLittleEndianInt(this.dataInputStream);

					if(days == 0 && time == 0) {
						recordObjects[i] = null;
					}
					else {
						Calendar calendar = new GregorianCalendar();
						calendar.setTimeInMillis(days * MILLISECS_PER_DAY + TIME_MILLIS_1_1_4713_BC + time);
						calendar.add(Calendar.MILLISECOND, -TimeZone.getDefault().getOffset(calendar.getTimeInMillis()));
						recordObjects[i] = calendar.getTime();
					}
					break;
				case MEMO:
					Number nBlock =  DBFUtils.readNumericStoredAsText(this.dataInputStream, field.getFieldLength());
					if (this.memoFile != null && nBlock != null) {						
						recordObjects[i] = memoFile.readText(nBlock.intValue());
					}
					break;
				case GENERAL_OLE:
				case BINARY:
				case PICTURE:
					Number nBlock1 =  DBFUtils.readNumericStoredAsText(this.dataInputStream, field.getFieldLength());
					if (this.memoFile != null && nBlock1 != null) {				
						System.out.println("  --->" + nBlock1);
						recordObjects[i] = memoFile.readBinary(nBlock1.intValue());
					}
					break;
				default:
					skip(field.getFieldLength());
					recordObjects[i] = "null";
				}
			}
		} catch (EOFException e) {
			return null;
		} catch (IOException e) {
			throw new DBFException(e.getMessage(), e);
		}

		return recordObjects;
	}

	/**
	 * function to safely skip n bytes (in some bufferd scenarios skip doesn't really skip all bytes)
	 * @param n
	 * @throws IOException
	 */
	private void skip(int n) throws IOException {
		int skipped = (int) this.dataInputStream.skip(n);
		for (int i = skipped; i < n; i++) {
			this.dataInputStream.readByte();
		}
	}
	
	protected DBFHeader getHeader() {
		return this.header;
	}

	public boolean isTrimRightSpaces() {
		return this.trimRightSpaces;
	}

	public void setTrimRightSpaces(boolean trimRightSpaces) {
		this.trimRightSpaces = trimRightSpaces;
	}
	
	public void setMemoFile(File memoFile) throws DBFException {
		if (!memoFile.exists()){
			throw new DBFException("Memo file " + memoFile.getName() + " not exists");
		}
		if (!memoFile.canRead()) {
			throw new DBFException("Cannot read Memo file " + memoFile.getName());
		}
		this.memoFile = new DBFMemoFile(memoFile, this.getCharset());
	}
	
	
}
