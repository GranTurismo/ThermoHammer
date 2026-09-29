using JsonDeserializerPro;

string dataFilePath = "./data.json";

using StreamReader reader = new(dataFilePath);

string content = await reader.ReadToEndAsync();

Deserializer.Deserialize<Customer>(content);
