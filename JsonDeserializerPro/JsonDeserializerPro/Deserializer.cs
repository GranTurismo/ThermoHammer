namespace JsonDeserializerPro;

public class Deserializer
{
    public static T Deserialize<T>(string json)
    {
        json = Normalize(json);
        Console.WriteLine(json);
        //string - propertyName, object - value
        Dictionary<string, object> keyValuePairs = new();

        while (true)
        {
            string key = GetKey(ref json);
            object value = GetValue(ref json);
            
            keyValuePairs.Add(key, value);
            break;
        }

        return default(T);
    }

    private static string Normalize(string json)
    {
        var newJson = string.Empty;
        
        bool isQuoted = false;
            
        foreach (var item in json)
        {
            if(item == '\"')
                isQuoted = !isQuoted;
            
            if ((item == ' ' || item == '\t' || item == '\n') &&  !isQuoted)
                    continue;
            
            newJson += item;
        }
        
        return newJson;
    }

    private static object GetValue(ref string json)
    {
        json = json.Trim();
        
        char seperator = ':';
        
        int indexOfSeperator = json.IndexOf(seperator);
        
        json = json.Remove(0, indexOfSeperator + 1).Trim();

        object value=null;

        if (json.StartsWith('\"'))
        {
            value = GetStringValue(ref json);
        }
        
        

        return value;
    }

    private static string GetStringValue(ref string json)
    {
        int index = json.IndexOf("\",");
        

        var value = json.Substring(0, index).Trim('\"');

        return value;
    }

    private static string GetKey(ref string json)
    {
        json = json.Trim();
        int startIndex =  json.IndexOf('\"') + 1;
        json = json.Remove(0, startIndex);
        
        int endIndex = json.IndexOf('\"');
        
        string key = json.Substring(0, endIndex);
        
        json = json.Remove(0, endIndex);
        
        return key;
    }
}