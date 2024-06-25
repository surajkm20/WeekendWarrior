const button = document.getElementById("search-button");
const input = document.getElementById("text-button");
const cityName = document.getElementById("city-name");
const cityLocation = document.getElementById("city-location");
const cityTemp = document.getElementById("city-temp");

async function getName(cityName){
    const promise = await fetch (`https://api.weatherapi.com/v1/current.json?key=aed2da91584d436fa18140031240206&q=${cityName}&aqi=yes`);
    return await promise.json();

}

button.addEventListener("click", async()=>{
    const value = input.value;
    const result = await getName(value);

    cityName.innerText = `${result.location.region}, ${result.location.country}`
    cityLocation.innerText = `${result.location.region}`
    cityTemp.innerText = `${result.current.temp_c}`
});


// https://api.weatherapi.com/v1/current.json?key=aed2da91584d436fa18140031240206&q=London&aqi=yes







