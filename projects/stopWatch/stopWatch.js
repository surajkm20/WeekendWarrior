const buttonStart = document.getElementById("start-button");
const buttonEnd = document.getElementById("start-end");


let i=0;
function increment(){
    buttonStart.innerText = i++;
    if(i == 10){
        clearInterval(interval);
        i = 0;
        buttonStart.innerText = "Reached Max count, press again to count";
    }
}

let interval = 0;
buttonStart.addEventListener("click", ()=>{
    interval = setInterval(increment, 1000);
});

buttonEnd.addEventListener("click", ()=>{
    clearInterval(interval);
    i = 0;
    buttonStart.innerText = "Press again to start:)☺️";
});


